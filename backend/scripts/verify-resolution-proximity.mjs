#!/usr/bin/env node
// Corre esto UNA VEZ contra el proyecto real de Firebase, después de haber
// desplegado firestore.rules + la Cloud Function verifyResolutionProximity,
// para cumplir el criterio de aceptación de #59: verificación en vivo con
// documentos sintéticos, mismo patrón que scripts/verify-dedup.mjs.
//
// Uso:
//   GOOGLE_APPLICATION_CREDENTIALS=/ruta/a/service-account.json node scripts/verify-resolution-proximity.mjs

import { initializeApp, applicationDefault } from 'firebase-admin/app';
import { getFirestore } from 'firebase-admin/firestore';
import { meshStateDocId } from '../lib/ids.mjs';

const credentialsPath = process.env.GOOGLE_APPLICATION_CREDENTIALS;
if (!credentialsPath) {
  console.error(
    'Falta GOOGLE_APPLICATION_CREDENTIALS — apuntá a la clave de cuenta de servicio del proyecto Firebase real.'
  );
  process.exit(1);
}

initializeApp({ credential: applicationDefault() });
const db = getFirestore();

async function waitForField(docRef, field, { timeoutMs = 20000, intervalMs = 300 } = {}) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const snap = await docRef.get();
    if (snap.get(field) !== undefined) return snap.get(field);
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
  throw new Error(`timeout esperando el campo "${field}" en ${docRef.path}`);
}

// Lima, Perú — mismas coordenadas de ejemplo que el resto de vectores del proyecto.
const VICTIM_LAT = -12.05;
const VICTIM_LON = -77.04;

async function verifyCase({ label, deviceIdHash, resolverLat, resolverLon, expected }) {
  const docId = meshStateDocId(deviceIdHash, 1);
  const docRef = db.collection('mesh_states').doc(docId);

  await docRef.set({
    device_id_hash: deviceIdHash,
    status: 'AYUDA',
    latitude: VICTIM_LAT,
    longitude: VICTIM_LON,
    beacon_timestamp: Math.floor(Date.now() / 1000),
    sequence: 1,
    uploaded_at: Math.floor(Date.now() / 1000),
  });

  await docRef.set(
    {
      resuelto: true,
      resuelto_por: 'verify-resolution-proximity-script',
      resuelto_en: Math.floor(Date.now() / 1000),
      resolutor_latitud_e7: Math.round(resolverLat * 1e7),
      resolutor_longitud_e7: Math.round(resolverLon * 1e7),
    },
    { merge: true }
  );

  const verified = await waitForField(docRef, 'proximidad_verificada');
  await docRef.delete();

  if (verified !== expected) {
    console.error(`✖ ${label}: se esperaba proximidad_verificada=${expected}, se obtuvo ${verified}`);
    process.exitCode = 1;
    return;
  }
  console.log(`✔ ${label}: proximidad_verificada=${verified}`);
}

async function verifyAtendiendoDoesNotTriggerProximity() {
  const deviceIdHash = 'verify-resolution-proximity-atendiendo';
  const docId = meshStateDocId(deviceIdHash, 1);
  const docRef = db.collection('mesh_states').doc(docId);

  await docRef.set({
    device_id_hash: deviceIdHash,
    status: 'AYUDA',
    latitude: VICTIM_LAT,
    longitude: VICTIM_LON,
    beacon_timestamp: Math.floor(Date.now() / 1000),
    sequence: 1,
    uploaded_at: Math.floor(Date.now() / 1000),
  });

  const { FieldValue } = await import('firebase-admin/firestore');
  await docRef.set(
    { atendido_por: FieldValue.arrayUnion({ device_id_hash: 'rescuer-x', marcado_en: Math.floor(Date.now() / 1000) }) },
    { merge: true }
  );

  // Da tiempo a que la función procese si es que fuera a hacerlo (no debería).
  await new Promise((resolve) => setTimeout(resolve, 5000));
  const snap = await docRef.get();
  const verified = snap.get('proximidad_verificada');
  await docRef.delete();

  if (verified !== undefined) {
    console.error(`✖ "atendiendo" no debe disparar la Cloud Function de proximidad, pero proximidad_verificada=${verified}`);
    process.exitCode = 1;
    return;
  }
  console.log('✔ "atendiendo" sin proximidad_verificada (no dispara la función, como se espera)');
}

async function main() {
  await verifyCase({
    label: 'resolutor a ~50m (dentro del radio de 100m)',
    deviceIdHash: 'verify-resolution-proximity-near',
    resolverLat: VICTIM_LAT + 0.00045, // ~50m en latitud
    resolverLon: VICTIM_LON,
    expected: true,
  });

  await verifyCase({
    label: 'resolutor a ~2km (fuera del radio de 100m)',
    deviceIdHash: 'verify-resolution-proximity-far',
    resolverLat: VICTIM_LAT + 0.018, // ~2km en latitud
    resolverLon: VICTIM_LON,
    expected: false,
  });

  await verifyAtendiendoDoesNotTriggerProximity();

  if (process.exitCode === 1) {
    console.error('\n✖ Verificación de proximidad falló.');
    process.exit(1);
  }
  console.log('\n✔ Verificación de proximidad completa contra el proyecto real.');
}

main().catch((err) => {
  console.error('Falló la verificación de proximidad:', err);
  process.exit(1);
});
