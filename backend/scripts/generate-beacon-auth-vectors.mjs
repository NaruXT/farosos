#!/usr/bin/env node
// Genera los vectores de prueba de autenticación del beacon (#39, Caso B):
// round-trip del layout Versión=0x02, device_id_hash derivado de pubkey,
// ECDH (Ed25519 dispositivo -> X25519, contra la pubkey X25519 real del
// backend) y MAC (HMAC-SHA256 truncado a 4 bytes). Implementación
// independiente del codec de cualquier plataforma (Swift/Kotlin), igual que
// los vectores legado de spec/test-vectors.json — usa @noble/curves (Ed25519
// + X25519) y el HMAC/SHA-256 nativo de Node.
//
// Si backend/secrets/ecdh-backend-keypair.json no existe, este script lo
// regenera a partir de una seed FIJA (ver PROTOTYPE_BACKEND_SEED_LABEL) y lo
// persiste ahí (fuera de git, ver backend/.gitignore). Si ya existe, la
// reusa (para no rotar el keypair del backend en cada corrida).
//
// Deliberadamente determinística por ahora: no existe todavía ningún backend
// real que dependa de este secreto (#48, la Cloud Function de verificación,
// no está implementada) — así que no hay nada que proteger con aleatoriedad
// real todavía, y priorizamos que "vectores de prueba... reproducibles" siga
// siendo cierto en un clon limpio del repo, aunque `secrets/` no viaje con
// git. Cuando #48 despliegue la Cloud Function real, ESA ticket debe generar
// un keypair nuevo con entropía real (`randomBytes`, resguardado en Firebase
// Secret Manager) y reemplazar esta constante — no reusar la determinística.
//
// La clave pública del dispositivo de prueba también es determinística
// (seed fija, documentada abajo) — es material de prueba público, no un
// secreto.
//
// Uso: node scripts/generate-beacon-auth-vectors.mjs
// Imprime por stdout el fragmento JSON a fusionar manualmente en
// spec/test-vectors.json (nunca incluye la clave privada del backend).

import { ed25519, x25519 } from '@noble/curves/ed25519.js';
import { bytesToHex } from '@noble/curves/utils.js';
import { createHash, createHmac } from 'node:crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const backendKeypairPath = path.join(repoRoot, 'backend', 'secrets', 'ecdh-backend-keypair.json');
const PROTOTYPE_BACKEND_SEED_LABEL = 'farosos-case-b-backend-ecdh-keypair-prototype-v1';

function seedFrom(label) {
  return createHash('sha256').update(label).digest();
}

function loadOrCreateBackendKeypair() {
  if (existsSync(backendKeypairPath)) {
    const stored = JSON.parse(readFileSync(backendKeypairPath, 'utf8'));
    return {
      publicKey: Buffer.from(stored.public_key_x25519_hex, 'hex'),
      secretKey: Buffer.from(stored.private_key_x25519_hex, 'hex'),
    };
  }

  const kp = x25519.keygen(seedFrom(PROTOTYPE_BACKEND_SEED_LABEL));
  mkdirSync(path.dirname(backendKeypairPath), { recursive: true });
  writeFileSync(
    backendKeypairPath,
    JSON.stringify(
      {
        $comment:
          'Keypair ECDH del backend (#39) — PROTOTIPO: derivado determinísticamente de una seed fija ' +
          `(SHA-256("${PROTOTYPE_BACKEND_SEED_LABEL}")), no de entropía real, porque hoy no hay ningún ` +
          'backend real que lo use (#48 aún no existe) y así un clon limpio del repo reproduce el mismo ' +
          'keypair sin depender de que este archivo sobreviva (aunque igual nunca se commitea — ver ' +
          'backend/.gitignore). Cuando #48 despliegue la Cloud Function real, generar un keypair nuevo con ' +
          'randomBytes() real y custodia en Firebase Secret Manager — no seguir usando este.',
        generated_at: new Date().toISOString(),
        public_key_x25519_hex: bytesToHex(kp.publicKey),
        private_key_x25519_hex: bytesToHex(kp.secretKey),
      },
      null,
      2
    ) + '\n'
  );
  return kp;
}

function leUnsigned32(n) {
  const buf = Buffer.alloc(4);
  buf.writeUIntLE(n >>> 0, 0, 4);
  return buf;
}

function leSigned32(n) {
  const buf = Buffer.alloc(4);
  buf.writeInt32LE(n, 0);
  return buf;
}

function authenticatedContent({ deviceIdHash, tipoEstado, latE7, lonE7, timestamp, ttl, sequence }) {
  return Buffer.concat([
    Buffer.from(deviceIdHash),
    Buffer.from([tipoEstado]),
    leSigned32(latE7),
    leSigned32(lonE7),
    leUnsigned32(timestamp),
    Buffer.from([ttl]),
    Buffer.from([sequence]),
  ]);
}

function encodeCaseB({ deviceIdHash, messageType, status, latE7, lonE7, timestamp, ttl, mac, sequence }) {
  const tipoEstado = ((messageType & 0x0f) << 4) | (status & 0x0f);
  return Buffer.concat([
    Buffer.from([0xe7]),
    Buffer.from([0x02]),
    Buffer.from([tipoEstado]),
    Buffer.from(deviceIdHash),
    leSigned32(latE7),
    leSigned32(lonE7),
    leUnsigned32(timestamp),
    Buffer.from([ttl]),
    Buffer.from(mac),
    Buffer.from([sequence]),
  ]);
}

function macOf(kSharedBytes, contentBytes) {
  return createHmac('sha256', Buffer.from(kSharedBytes)).update(Buffer.from(contentBytes)).digest().subarray(0, 4);
}

const backendKp = loadOrCreateBackendKeypair();

// Identidad de dispositivo de prueba, determinística y pública (no un dispositivo real).
const deviceSeed = seedFrom('farosos-case-b-test-device-v1');
const deviceSecretKey = ed25519.keygen(deviceSeed).secretKey;
const devicePublicKey = ed25519.getPublicKey(deviceSecretKey);
const deviceIdHash = createHash('sha256').update(devicePublicKey).digest().subarray(0, 6);

// Conversión Ed25519 -> X25519 (birracional, equivalente a
// crypto_sign_ed25519_*_to_curve25519 de libsodium) para el ECDH.
const deviceX25519Priv = ed25519.utils.toMontgomerySecret(deviceSecretKey);
const deviceX25519Pub = ed25519.utils.toMontgomery(devicePublicKey);
const kShared = x25519.getSharedSecret(deviceX25519Priv, backendKp.publicKey);

// Sanity: el backend debe derivar el mismo secreto desde su propio lado
// (privkey backend + pubkey X25519 del dispositivo) — si esto no coincide,
// el ECDH está mal y no tiene sentido publicar los vectores.
const kSharedFromBackendSide = x25519.getSharedSecret(backendKp.secretKey, deviceX25519Pub);
if (bytesToHex(kSharedFromBackendSide) !== bytesToHex(kShared)) {
  throw new Error('ECDH no es simétrico — revisar la conversión Ed25519->X25519 o el keypair del backend.');
}

const caseBSpecs = [
  { name: 'caso_b_estado_sin_confirmar', status: 0, latE7: 194326000, lonE7: -991332000, timestamp: 1700010000, ttl: 16, sequence: 0 },
  { name: 'caso_b_estado_ok', status: 1, latE7: 405000000, lonE7: -740000000, timestamp: 1700010500, ttl: 12, sequence: 1 },
  { name: 'caso_b_estado_ayuda', status: 2, latE7: -334489000, lonE7: -707679000, timestamp: 1700011000, ttl: 9, sequence: 2 },
  { name: 'caso_b_estado_silencio_timeout', status: 3, latE7: -122419000, lonE7: -709320000, timestamp: 1700011500, ttl: 3, sequence: 3 },
  { name: 'caso_b_estado_gateway_disponible', status: 4, latE7: 65000000, lonE7: -785000000, timestamp: 1700012000, ttl: 16, sequence: 0 },
  { name: 'caso_b_edge_ttl_secuencia_limite', status: 1, latE7: 0, lonE7: 0, timestamp: 1700012500, ttl: 0, sequence: 255 },
];

const caseBVectors = caseBSpecs.map((spec) => {
  const messageType = 0; // BEACON — único Tipo definido hoy para Versión=0x02
  const tipoEstado = ((messageType & 0x0f) << 4) | (spec.status & 0x0f);
  const content = authenticatedContent({ deviceIdHash, tipoEstado, ...spec, latE7: spec.latE7, lonE7: spec.lonE7 });
  const mac = macOf(kShared, content);
  const bytes = encodeCaseB({ deviceIdHash, messageType, mac, ...spec });
  return {
    name: spec.name,
    fields: {
      magic: '0xE7',
      version: 2,
      message_type: messageType,
      status: spec.status,
      device_id_hash: bytesToHex(deviceIdHash),
      latitude_e7: spec.latE7,
      longitude_e7: spec.lonE7,
      timestamp: spec.timestamp,
      ttl: spec.ttl,
      mac: bytesToHex(mac),
      sequence: spec.sequence,
    },
    bytes_hex: bytesToHex(bytes),
    content_hex: bytesToHex(content),
  };
});

const output = {
  case_b: {
    $comment:
      'Layout Versión=0x02 (Caso B, #38/#39). Offsets en spec/packet-format.md. ' +
      'message_type/status son los mismos enums MessageType/Status del layout legado — se empaquetan ' +
      'en un byte (TipoEstado: nibble alto=message_type, nibble bajo=status) solo en este layout.',
    version: 2,
    packet_size_bytes: 27,
    field_order: [
      'magic',
      'version',
      'tipo_estado',
      'device_id_hash',
      'latitude_e7',
      'longitude_e7',
      'timestamp',
      'ttl',
      'mac',
      'sequence',
    ],
    vectors: caseBVectors,
  },
  device_id_hash_vectors: [
    {
      name: 'device_id_hash_desde_pubkey_ed25519',
      $comment: 'device_id_hash = SHA-256(clave pública Ed25519)[:6] — reemplaza SHA-256(UUID)[:6] (ver #38, #40/#41).',
      public_key_ed25519_hex: bytesToHex(devicePublicKey),
      device_id_hash: bytesToHex(deviceIdHash),
    },
  ],
  ecdh: {
    $comment:
      'K_shared = X25519(privkey_dispositivo_convertida, pubkey_X25519_backend). La privkey/pubkey Ed25519 ' +
      'del dispositivo se convierten a X25519 vía el mapa birracional estándar (equivalente a ' +
      'crypto_sign_ed25519_sk_to_curve25519/_pk_to_curve25519 de libsodium) — no es un keypair X25519 aparte. ' +
      'backend_public_key_x25519_hex es la constante real que ambas apps deben embeber.',
    backend_public_key_x25519_hex: bytesToHex(backendKp.publicKey),
    vectors: [
      {
        name: 'ecdh_dispositivo_prueba_contra_backend',
        device_secret_key_ed25519_hex: bytesToHex(deviceSecretKey),
        device_public_key_ed25519_hex: bytesToHex(devicePublicKey),
        $comment_x25519:
          'device_secret_key_x25519_hex/device_public_key_x25519_hex son la conversión Ed25519->X25519 ya aplicada ' +
          '(ver campo ecdh.$comment) — se publican en claro porque esta es una identidad de PRUEBA, no un dispositivo real. ' +
          'Permiten verificar K_shared con crypto nativo (X25519 puro) sin depender de @noble/curves ni reimplementar la conversión.',
        device_secret_key_x25519_hex: bytesToHex(deviceX25519Priv),
        device_public_key_x25519_hex: bytesToHex(deviceX25519Pub),
        backend_public_key_x25519_hex: bytesToHex(backendKp.publicKey),
        expected_k_shared_hex: bytesToHex(kShared),
      },
    ],
  },
  mac_vectors: caseBVectors.map((v) => ({
    name: `mac_${v.name}`,
    $comment: 'content_hex = concatenación de (device_id_hash, tipo_estado, latitud, longitud, timestamp, ttl, secuencia) — ver spec/packet-format.md.',
    k_shared_hex: bytesToHex(kShared),
    content_hex: v.content_hex,
    expected_mac_hex: v.fields.mac,
  })),
};

console.log(JSON.stringify(output, null, 2));
