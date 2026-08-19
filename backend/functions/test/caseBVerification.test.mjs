// Tests puros de backend/functions/lib/caseBVerification.mjs — sin
// Firestore ni emulador, corren con `node --test` directo (mismo principio
// que backend/tests/beaconAuthVectors.test.mjs, pero desde dentro de
// functions/ porque esta lógica se despliega como parte de la función y
// necesita sus propias dependencias declaradas en functions/package.json).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { ed25519, x25519 } from '@noble/curves/ed25519.js';
import { bytesToHex } from '@noble/curves/utils.js';
import {
  authenticatedContent,
  computeMac,
  deriveKShared,
  devicePublicKeyX25519FromEd25519,
  verifyCaseBMac,
} from '../lib/caseBVerification.mjs';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const vectors = JSON.parse(readFileSync(path.join(repoRoot, 'spec', 'test-vectors.json'), 'utf8'));

test('authenticatedContent matches every Caso B vector', () => {
  for (const vector of vectors.case_b.vectors) {
    const content = authenticatedContent({
      deviceIdHash: Buffer.from(vector.fields.device_id_hash, 'hex'),
      messageType: vector.fields.message_type,
      status: vector.fields.status,
      latitudeE7: vector.fields.latitude_e7,
      longitudeE7: vector.fields.longitude_e7,
      timestamp: vector.fields.timestamp,
      ttl: vector.fields.ttl,
      sequence: vector.fields.sequence,
    });
    assert.equal(bytesToHex(content), vector.content_hex, vector.name);
  }
});

test('computeMac matches every mac_vectors entry', () => {
  for (const vector of vectors.mac_vectors) {
    const mac = computeMac(Buffer.from(vector.k_shared_hex, 'hex'), Buffer.from(vector.content_hex, 'hex'));
    assert.equal(bytesToHex(mac), vector.expected_mac_hex, vector.name);
  }
});

// `deriveKShared` recibe la clave privada X25519 del BACKEND — un dato que
// nunca se publica en spec/test-vectors.json (ni siquiera la determinística
// de prueba, ver backend/scripts/generate-beacon-auth-vectors.mjs). Se
// verifica con un keypair de prueba propio, autocontenido (no un secreto
// real), aprovechando la propiedad de simetría del ECDH: el K_shared que
// calcula el backend (privkey_backend, pubkey_X25519_dispositivo) debe
// coincidir con el que calcularía el dispositivo (privkey_X25519_dispositivo,
// pubkey_backend) — este segundo lado sí está publicado en el vector `ecdh`.
test('deriveKShared es simétrico contra el vector de ECDH publicado', () => {
  const testBackendKeypair = x25519.keygen(Buffer.alloc(32, 0x42));
  const ecdhVector = vectors.ecdh.vectors[0];
  const devicePublicKeyEd25519 = Buffer.from(ecdhVector.device_public_key_ed25519_hex, 'hex');
  const deviceX25519SecretKey = Buffer.from(ecdhVector.device_secret_key_x25519_hex, 'hex');

  const kSharedFromBackendSide = deriveKShared(testBackendKeypair.secretKey, devicePublicKeyEd25519);
  const kSharedFromDeviceSide = x25519.getSharedSecret(deviceX25519SecretKey, testBackendKeypair.publicKey);

  assert.equal(bytesToHex(kSharedFromBackendSide), bytesToHex(kSharedFromDeviceSide));
});

test('devicePublicKeyX25519FromEd25519 coincide con el vector publicado', () => {
  const ecdhVector = vectors.ecdh.vectors[0];
  const converted = devicePublicKeyX25519FromEd25519(Buffer.from(ecdhVector.device_public_key_ed25519_hex, 'hex'));
  assert.equal(bytesToHex(converted), ecdhVector.device_public_key_x25519_hex);
});

// `verifyCaseBMac` es la función que consume la Cloud Function real — se
// prueba con un escenario sintético autoconsistente (keypair de prueba +
// contenido armado con las mismas funciones puras ya verificadas arriba
// contra vectores externos), confirmando que acepta el MAC correcto y
// rechaza cualquier alteración — mismo principio que
// `testChangingAnyAuthenticatedFieldChangesTheMac` en iOS/Android (#42/#43).
test('verifyCaseBMac', async (t) => {
  const testBackendKeypair = x25519.keygen(Buffer.alloc(32, 0x42));
  const ecdhVector = vectors.ecdh.vectors[0];
  const devicePublicKeyEd25519Hex = ecdhVector.device_public_key_ed25519_hex;
  const backendPrivateKeyX25519Hex = bytesToHex(testBackendKeypair.secretKey);

  const kShared = deriveKShared(testBackendKeypair.secretKey, Buffer.from(devicePublicKeyEd25519Hex, 'hex'));
  const baseFields = {
    deviceIdHash: Buffer.from('1dcfc6d36638', 'hex'),
    messageType: 0,
    status: 1,
    latitudeE7: 194326000,
    longitudeE7: -991332000,
    timestamp: 1700010000,
    ttl: 16,
    sequence: 0,
  };
  const mac = computeMac(kShared, authenticatedContent(baseFields));

  function meshStateFrom(overrides = {}) {
    return {
      device_id_hash: baseFields.deviceIdHash.toString('hex'),
      message_type: baseFields.messageType,
      status: baseFields.status,
      latitude_e7: baseFields.latitudeE7,
      longitude_e7: baseFields.longitudeE7,
      beacon_timestamp: baseFields.timestamp,
      ttl: baseFields.ttl,
      sequence: baseFields.sequence,
      mac: bytesToHex(mac),
      ...overrides,
    };
  }

  await t.test('acepta un MAC válido', () => {
    assert.equal(
      verifyCaseBMac({ meshState: meshStateFrom(), devicePublicKeyEd25519Hex, backendPrivateKeyX25519Hex }),
      true
    );
  });

  await t.test('rechaza un MAC alterado', () => {
    assert.equal(
      verifyCaseBMac({
        meshState: meshStateFrom({ mac: 'ffffffff' }),
        devicePublicKeyEd25519Hex,
        backendPrivateKeyX25519Hex,
      }),
      false
    );
  });

  const tamperedFields = ['status', 'latitude_e7', 'longitude_e7', 'beacon_timestamp', 'ttl', 'sequence'];
  for (const field of tamperedFields) {
    await t.test(`rechaza un contenido alterado (${field})`, () => {
      const tampered = meshStateFrom({ [field]: meshStateFrom()[field] + 1 });
      assert.equal(verifyCaseBMac({ meshState: tampered, devicePublicKeyEd25519Hex, backendPrivateKeyX25519Hex }), false);
    });
  }

  await t.test('rechaza cuando la clave pública del dispositivo no coincide (otra identidad Ed25519 válida)', () => {
    const otherDeviceSecretKey = ed25519.utils.randomSecretKey();
    const otherDevicePublicKey = ed25519.getPublicKey(otherDeviceSecretKey);
    assert.equal(
      verifyCaseBMac({
        meshState: meshStateFrom(),
        devicePublicKeyEd25519Hex: bytesToHex(otherDevicePublicKey),
        backendPrivateKeyX25519Hex,
      }),
      false
    );
  });

  await t.test('no lanza con datos mal formados — se trata como no verificado', () => {
    assert.equal(
      verifyCaseBMac({
        meshState: meshStateFrom({ device_id_hash: 'no-es-hex-valido' }),
        devicePublicKeyEd25519Hex,
        backendPrivateKeyX25519Hex,
      }),
      false
    );
  });
});
