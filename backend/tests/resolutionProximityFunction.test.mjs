// Integración real de #59: escribe documentos sintéticos directo en el
// emulador de Firestore y confirma que la Cloud Function real (corriendo en
// el emulador de Functions, ver package.json → "test") se dispara sola y
// marca `proximidad_verificada` — sin invocar el handler a mano, mismo
// principio que caseBVerificationFunction.test.mjs (#48). Usa el Admin SDK
// (bypassa Security Rules, ya cubiertas por firestore-rules.test.mjs).
import { before, beforeEach, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { initializeApp, getApps } from 'firebase-admin/app';
import { getFirestore } from 'firebase-admin/firestore';
import { waitForField, createTrackedWriter } from './firestoreFunctionTestHelpers.mjs';

let db;
const writer = createTrackedWriter();

before(async () => {
  // `caseBVerificationFunction.test.mjs` puede haber corrido antes en el
  // mismo proceso de `node --test tests/*.test.mjs` e inicializado ya la
  // app default del Admin SDK — reusarla en vez de que `initializeApp()`
  // explote con "already exists".
  db = getApps().length ? getFirestore() : getFirestore(initializeApp({ projectId: 'farosos-rules-test' }));

  // Cold start propio de `verifyResolutionProximity` — es una función
  // distinta a `verifyCaseBMeshState`, con su propio arranque en frío
  // aunque corran en el mismo emulador (mismo motivo que el warm-up de
  // #48, ver ese archivo).
  const warmUpRef = db.collection('mesh_states').doc('warm_up_resolution_0');
  await warmUpRef.set({
    device_id_hash: 'warm_up',
    sequence: 0,
    latitude: -12.05,
    longitude: -77.04,
    resuelto: true,
    resolutor_latitud_e7: -120500000,
    resolutor_longitud_e7: -770400000,
  });
  await waitForField(warmUpRef, 'proximidad_verificada', { timeoutMs: 30000 });
  await warmUpRef.delete();
});

beforeEach(() => writer.cleanup());

describe('Cloud Function de verificación de proximidad (#59)', () => {
  it('marca proximidad_verificada: true cuando el resolutor está a <=100m de la víctima', async () => {
    const ref = db.collection('mesh_states').doc('abc123_1');
    await writer.set(ref, {
      device_id_hash: 'abc123',
      sequence: 1,
      latitude: -12.05,
      longitude: -77.04,
      resuelto: true,
      resuelto_por: 'resolver-hash',
      resuelto_en: 1755000100,
      resolutor_latitud_e7: -120505000, // ~55.6m de distancia
      resolutor_longitud_e7: -770400000,
    });

    const verified = await waitForField(ref, 'proximidad_verificada');
    assert.equal(verified, true);
  });

  it('marca proximidad_verificada: false cuando el resolutor está a más de 100m', async () => {
    const ref = db.collection('mesh_states').doc('abc123_2');
    await writer.set(ref, {
      device_id_hash: 'abc123',
      sequence: 2,
      latitude: -12.05,
      longitude: -77.04,
      resuelto: true,
      resuelto_por: 'resolver-hash',
      resuelto_en: 1755000100,
      resolutor_latitud_e7: -120520000, // ~222m de distancia
      resolutor_longitud_e7: -770400000,
    });

    const verified = await waitForField(ref, 'proximidad_verificada');
    assert.equal(verified, false);
  });

  it('marca proximidad_verificada: null (nunca oculta el documento) cuando falta la ubicación de la víctima', async () => {
    // Documento parcial: la resolución llegó antes que el beacon original
    // de la víctima (#55) — sin latitude/latitude_e7.
    const ref = db.collection('mesh_states').doc('nuevo999_7');
    await writer.set(ref, {
      device_id_hash: 'nuevo999',
      sequence: 7,
      resuelto: true,
      resuelto_por: 'resolver-hash',
      resuelto_en: 1755000100,
      resolutor_latitud_e7: -120500000,
      resolutor_longitud_e7: -770400000,
    });

    const verified = await waitForField(ref, 'proximidad_verificada');
    assert.equal(verified, null);

    const snap = await ref.get();
    assert.equal(snap.exists, true, 'el documento no se oculta ni se descarta');
    assert.equal(snap.get('resuelto'), true, 'los campos de resolución siguen presentes');
  });

  it('no toca documentos que solo marcan "atendiendo" (sin resuelto, sin proximidad — #55)', async () => {
    const ref = db.collection('mesh_states').doc('abc123_3');
    await writer.set(ref, {
      device_id_hash: 'abc123',
      sequence: 3,
      latitude: -12.05,
      longitude: -77.04,
      atendido_por: [{ device_id_hash: 'r1', marcado_en: 1755000100 }],
    });

    // Sin campo "resuelto": nada que esperar con waitForField (nunca va a
    // aparecer) — se da un margen fijo y se confirma que sigue ausente.
    await new Promise((resolve) => setTimeout(resolve, 1500));
    const snap = await ref.get();
    assert.equal(snap.get('proximidad_verificada'), undefined);
  });

  it('no vuelve a dispararse a partir de su propia escritura de proximidad_verificada (sin loop)', async () => {
    const ref = db.collection('mesh_states').doc('abc123_4');
    await writer.set(ref, {
      device_id_hash: 'abc123',
      sequence: 4,
      latitude: -12.05,
      longitude: -77.04,
      resuelto: true,
      resuelto_por: 'resolver-hash',
      resuelto_en: 1755000100,
      resolutor_latitud_e7: -120505000,
      resolutor_longitud_e7: -770400000,
    });

    await waitForField(ref, 'proximidad_verificada');
    const snapAfterCompute = await ref.get();
    const updateTimeAfterCompute = snapAfterCompute.updateTime;

    // Margen generoso sobre la latencia típica de la función en el
    // emulador (~decenas/pocos cientos de ms una vez caliente, ver #48) —
    // si hubiera loop, una segunda escritura ocurriría en esta ventana.
    await new Promise((resolve) => setTimeout(resolve, 1500));

    const snapLater = await ref.get();
    assert.equal(
      snapLater.updateTime.isEqual(updateTimeAfterCompute),
      true,
      'no debe haber ninguna escritura adicional del documento (loop de re-disparo)'
    );
  });
});
