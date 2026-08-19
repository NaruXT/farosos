// Integración real de #48: escribe documentos sintéticos (`participants` +
// `mesh_states` version=2) directo en el emulador de Firestore y confirma
// que la Cloud Function real (corriendo en el emulador de Functions, ver
// package.json → "test") se dispara sola y marca `mac_verificado` — sin
// invocar el handler a mano, para probar de verdad el AC "se dispara
// automáticamente al escribirse un documento". Usa el Admin SDK (bypassa
// Security Rules, ya cubiertas por firestore-rules.test.mjs) — mismo
// principio que scripts/verify-dedup.mjs.
import { before, beforeEach, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { initializeApp } from 'firebase-admin/app';
import { getFirestore } from 'firebase-admin/firestore';
import { ed25519 } from '@noble/curves/ed25519.js';
import { bytesToHex } from '@noble/curves/utils.js';
import { authenticatedContent, computeMac, deriveKShared } from '../functions/lib/caseBVerification.mjs';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { waitForField, createTrackedWriter } from './firestoreFunctionTestHelpers.mjs';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
// Misma clave real que backend/functions/.secret.local le da al emulador de
// Functions — así el MAC que arma este test es el que la función real
// espera. Nunca se lee la privada de acá para nada más que construir el
// vector de prueba (nunca se sube a Firestore).
const realBackendKeypair = JSON.parse(
  readFileSync(path.join(repoRoot, 'backend', 'secrets', 'ecdh-backend-real-keypair.json'), 'utf8')
);

let db;
const writer = createTrackedWriter();

before(async () => {
  initializeApp({ projectId: 'farosos-rules-test' });
  db = getFirestore();

  // El primer disparo real de la función incluye su cold start (cargar
  // firebase-admin/firebase-functions/@noble/curves por primera vez en el
  // worker del emulador) — se puede tomar más que el timeout normal de
  // `waitForField`. Se absorbe acá, fuera de los tests con aserciones, con
  // un margen generoso; las invocaciones siguientes ya miden en ms (ver
  // logs del emulador).
  const warmUpRef = db.collection('mesh_states').doc('warm_up_0');
  await warmUpRef.set({ version: 2, device_id_hash: 'warm_up', mac: 'ffffffff', sequence: 0 });
  await waitForField(warmUpRef, 'mac_verificado', { timeoutMs: 30000 });
  await warmUpRef.delete();
});

beforeEach(() => writer.cleanup());

// `device_id_hash = SHA-256(pubkey Ed25519)[:6]` — misma derivación que
// `DeviceIdentityHash` (iOS/Android, #39/#40/#41), para que el fixture de
// este test se comporte como un dispositivo real.
function randomDeviceIdentity() {
  const secretKey = ed25519.utils.randomSecretKey();
  const publicKey = ed25519.getPublicKey(secretKey);
  const deviceIdHash = bytesToHex(createHash('sha256').update(publicKey).digest().subarray(0, 6));
  return { secretKey, publicKey, deviceIdHash };
}

function caseBFields(deviceIdHash, overrides = {}) {
  return {
    messageType: 0,
    status: 1,
    latitudeE7: 194326000,
    longitudeE7: -991332000,
    timestamp: 1700010000,
    ttl: 16,
    sequence: 0,
    ...overrides,
    deviceIdHash: Buffer.from(deviceIdHash, 'hex'),
  };
}

describe('Cloud Function de verificación de MAC (#48)', () => {
  it('marca verificado un beacon Caso B con MAC válido', async () => {
    const device = randomDeviceIdentity();
    await writer.set(db.collection('participants').doc(device.deviceIdHash), {
      device_id_hash: device.deviceIdHash,
      public_key_ed25519: bytesToHex(device.publicKey),
      name: 'Prueba de integración #48',
    });

    const fields = caseBFields(device.deviceIdHash);
    const kShared = deriveKShared(Buffer.from(realBackendKeypair.private_key_x25519_hex, 'hex'), device.publicKey);
    const mac = computeMac(kShared, authenticatedContent(fields));

    const docRef = db.collection('mesh_states').doc(`${device.deviceIdHash}_0`);
    await writer.set(docRef, {
      version: 2,
      device_id_hash: device.deviceIdHash,
      message_type: fields.messageType,
      status: fields.status,
      latitude_e7: fields.latitudeE7,
      longitude_e7: fields.longitudeE7,
      beacon_timestamp: fields.timestamp,
      ttl: fields.ttl,
      sequence: fields.sequence,
      mac: bytesToHex(mac),
      uploaded_at: Math.floor(Date.now() / 1000),
    });

    const verified = await waitForField(docRef, 'mac_verificado');
    assert.equal(verified, true);
  });

  it('marca señalado (nunca borra) un beacon Caso B con MAC inválido', async () => {
    const device = randomDeviceIdentity();
    await writer.set(db.collection('participants').doc(device.deviceIdHash), {
      device_id_hash: device.deviceIdHash,
      public_key_ed25519: bytesToHex(device.publicKey),
      name: 'Prueba de integración #48',
    });

    const docRef = db.collection('mesh_states').doc(`${device.deviceIdHash}_0`);
    await writer.set(docRef, {
      version: 2,
      device_id_hash: device.deviceIdHash,
      message_type: 0,
      status: 1,
      latitude_e7: 194326000,
      longitude_e7: -991332000,
      beacon_timestamp: 1700010000,
      ttl: 16,
      sequence: 0,
      mac: 'ffffffff', // MAC deliberadamente incorrecto
      uploaded_at: Math.floor(Date.now() / 1000),
    });

    const verified = await waitForField(docRef, 'mac_verificado');
    assert.equal(verified, false);

    const snap = await docRef.get();
    assert.equal(snap.exists, true, 'el documento nunca debe borrarse');
  });

  it('no toca beacons Versión=0x01 (Caso A) — sin campo version', async () => {
    const docRef = db.collection('mesh_states').doc('legacy_0');
    await writer.set(docRef, {
      device_id_hash: 'legacy',
      status: 'AYUDA',
      latitude: -12.05,
      longitude: -77.04,
      beacon_timestamp: 1700010000,
      sequence: 0,
      uploaded_at: Math.floor(Date.now() / 1000),
      confirmed_by_gateways: ['gw1'],
    });

    // No hay campo que esperar — se confirma con un margen de tiempo que la
    // función no le agregó `mac_verificado` (si la función procesara esto
    // por error, el campo aparecería).
    await new Promise((resolve) => setTimeout(resolve, 2000));
    const snap = await docRef.get();
    assert.equal(snap.get('mac_verificado'), undefined);
  });
});
