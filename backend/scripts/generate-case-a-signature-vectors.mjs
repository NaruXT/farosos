#!/usr/bin/env node
// Genera los vectores de prueba de fragmentación de firma (Caso A, #38/#44):
// una identidad Ed25519 de prueba, su autocertificado (firma sobre su propia
// clave pública), y los 7 fragmentos `FRAGMENTO_FIRMA` (layout legado,
// Tipo=3) que la codifican dentro del límite de 26 bytes. Implementación
// independiente del codec de cualquier plataforma (Swift/Kotlin), igual que
// el resto de generadores de spec/test-vectors.json — usa @noble/curves.
//
// Identidad de prueba determinística (seed fija, documentada abajo) — es
// material de prueba público, no un dispositivo real.
//
// Uso: node scripts/generate-case-a-signature-vectors.mjs
// Imprime por stdout el fragmento JSON a fusionar manualmente en
// spec/test-vectors.json, clave `fragmento_firma`.

import { ed25519 } from '@noble/curves/ed25519.js';
import { bytesToHex } from '@noble/curves/utils.js';
import { createHash } from 'node:crypto';

function seedFrom(label) {
  return createHash('sha256').update(label).digest();
}

// Identidad de dispositivo de prueba, determinística y pública.
const deviceSeed = seedFrom('farosos-case-a-test-device-v1');
const deviceSecretKey = ed25519.keygen(deviceSeed).secretKey;
const devicePublicKey = ed25519.getPublicKey(deviceSecretKey);
const deviceIdHash = createHash('sha256').update(devicePublicKey).digest().subarray(0, 6);

// Autocertificado: el dispositivo firma su propia clave pública.
const signature = ed25519.sign(devicePublicKey, deviceSecretKey);
if (!ed25519.verify(signature, devicePublicKey, devicePublicKey)) {
  throw new Error('La firma generada no verifica contra su propia pubkey — no tiene sentido publicar el vector.');
}

const PAYLOAD_CHUNK_SIZE = 15;
const FRAGMENT_COUNT = 7; // ceil(96 / 15)
const payload = Buffer.concat([Buffer.from(devicePublicKey), Buffer.from(signature)]); // 96 bytes
if (payload.length !== 32 + 64) {
  throw new Error('payload debe medir 96 bytes (32 pubkey + 64 firma)');
}

function fragHeaderByte(index, count) {
  return ((index & 0x0f) << 4) | (count & 0x0f);
}

function encodeFragment({ deviceIdHash: hash, ttl, index, count, chunk }) {
  const paddedChunk = Buffer.alloc(PAYLOAD_CHUNK_SIZE);
  chunk.copy(paddedChunk);
  return Buffer.concat([
    Buffer.from([0xe7]), // Magic
    Buffer.from([0x01]), // Versión (legado)
    Buffer.from([0x03]), // Tipo = FRAGMENTO_FIRMA
    Buffer.from(hash),
    Buffer.from([ttl]),
    Buffer.from([fragHeaderByte(index, count)]),
    paddedChunk,
  ]);
}

const ttl = 16; // recién emitido, mismo valor inicial que el resto del layout legado
const fragments = [];
for (let index = 0; index < FRAGMENT_COUNT; index += 1) {
  const start = index * PAYLOAD_CHUNK_SIZE;
  const end = Math.min(start + PAYLOAD_CHUNK_SIZE, payload.length);
  const chunk = payload.subarray(start, end);
  const bytes = encodeFragment({ deviceIdHash, ttl, index, count: FRAGMENT_COUNT, chunk });
  fragments.push({
    name: `fragmento_firma_indice_${index}`,
    fields: {
      magic: '0xE7',
      version: 1,
      tipo: 3,
      device_id_hash: bytesToHex(deviceIdHash),
      ttl,
      frag_index: index,
      frag_count: FRAGMENT_COUNT,
      chunk_hex: bytesToHex(chunk),
      chunk_len: chunk.length,
    },
    bytes_hex: bytesToHex(bytes),
  });
}
if (fragments.length !== FRAGMENT_COUNT) {
  throw new Error(`se esperaban ${FRAGMENT_COUNT} fragmentos, se generaron ${fragments.length}`);
}
if (fragments[FRAGMENT_COUNT - 1].fields.chunk_len !== payload.length - (FRAGMENT_COUNT - 1) * PAYLOAD_CHUNK_SIZE) {
  throw new Error('el último fragmento no tiene el largo real esperado');
}

const output = {
  fragmento_firma: {
    $comment:
      'Fragmentación de firma Caso A (#38/#44) — layout legado (Versión=0x01), Tipo=3 (FRAGMENTO_FIRMA). ' +
      'payload_hex = pubkey Ed25519 (32B) || firma Ed25519 (64B) = 96 bytes, cortado en 7 fragmentos de 15 ' +
      'bytes (el 7º trae 6 bytes reales + 9 de relleno con ceros — chunk_hex/chunk_len de cada fragmento ya ' +
      'reflejan el contenido REAL sin relleno; bytes_hex sí incluye el relleno de ceros en el payload de 15 ' +
      'bytes del paquete completo). frag_index/frag_count van empacados en un solo byte FragHeader (nibble ' +
      'alto=índice, nibble bajo=conteo). La firma es un autocertificado: Ed25519_Sign(privkey, pubkey) — el ' +
      'dispositivo firma su propia clave pública, no el contenido de ningún beacon individual.',
    version: 1,
    message_type: 3,
    packet_size_bytes: 26,
    payload_chunk_size_bytes: PAYLOAD_CHUNK_SIZE,
    fragment_count: FRAGMENT_COUNT,
    field_order: ['magic', 'version', 'tipo', 'device_id_hash', 'ttl', 'frag_header', 'payload'],
    identity: {
      device_secret_key_ed25519_hex: bytesToHex(deviceSecretKey),
      device_public_key_ed25519_hex: bytesToHex(devicePublicKey),
      device_id_hash: bytesToHex(deviceIdHash),
      signature_hex: bytesToHex(signature),
      $comment_signature: 'signature = Ed25519_Sign(device_secret_key, device_public_key) — autocertificado.',
    },
    payload_hex: bytesToHex(payload),
    fragments,
  },
};

console.log(JSON.stringify(output, null, 2));
