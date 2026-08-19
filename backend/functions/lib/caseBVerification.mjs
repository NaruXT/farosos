// Verificación de MAC de Caso B (#38/#48) — lógica pura, sin dependencias de
// Firebase/Firestore, para poder testear con `node --test` sin emulador
// (mismo principio que backend/lib/ids.mjs y backend/public/js/meshView.mjs:
// la lógica de negocio vive separada del glue de infraestructura).
//
// Reimplementa exactamente el mismo esquema que
// backend/scripts/generate-beacon-auth-vectors.mjs (fuente de los vectores
// de #39) y CaseBAuthentication.swift/.kt (iOS/Android, #42/#43):
// - K_shared = X25519(privkey_X25519_backend, pubkey_Ed25519_dispositivo
//   convertida a X25519 vía el mapa birracional estándar).
// - MAC = HMAC-SHA256(K_shared, contenido_autenticado)[:4].
// - contenido_autenticado = device_id_hash(6) || tipo_estado(1) ||
//   latitud_e7(4, LE con signo) || longitud_e7(4, LE con signo) ||
//   timestamp(4, LE sin signo) || ttl(1) || secuencia(1).

import { ed25519, x25519 } from '@noble/curves/ed25519.js';
import { createHmac } from 'node:crypto';

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

/** Clave pública X25519 del dispositivo, derivada de su clave pública Ed25519
 * (la que sube el registro opt-in, #46/#47) — el dispositivo nunca tiene ni
 * sube una clave X25519 aparte, solo su identidad Ed25519. */
export function devicePublicKeyX25519FromEd25519(devicePublicKeyEd25519) {
  return ed25519.utils.toMontgomery(devicePublicKeyEd25519);
}

/** `K_shared = X25519(privkey_X25519_backend, pubkey_X25519_dispositivo)`. */
export function deriveKShared(backendPrivateKeyX25519, devicePublicKeyEd25519) {
  const deviceX25519Pub = devicePublicKeyX25519FromEd25519(devicePublicKeyEd25519);
  return x25519.getSharedSecret(backendPrivateKeyX25519, deviceX25519Pub);
}

/** `contenido = DeviceIdHash(6) || TipoEstado(1) || Latitud(4) || Longitud(4) || Timestamp(4) || TTL(1) || Secuencia(1)` — 21 bytes.
 * No incluye `Magic`, `Versión` ni el propio `MAC`. */
export function authenticatedContent({ deviceIdHash, messageType, status, latitudeE7, longitudeE7, timestamp, ttl, sequence }) {
  const tipoEstado = ((messageType & 0x0f) << 4) | (status & 0x0f);
  return Buffer.concat([
    Buffer.from(deviceIdHash),
    Buffer.from([tipoEstado]),
    leSigned32(latitudeE7),
    leSigned32(longitudeE7),
    leUnsigned32(timestamp),
    Buffer.from([ttl]),
    Buffer.from([sequence]),
  ]);
}

/** `MAC = HMAC-SHA256(K_shared, contenido)[:4]`. */
export function computeMac(kSharedBytes, contentBytes) {
  return createHmac('sha256', Buffer.from(kSharedBytes)).update(Buffer.from(contentBytes)).digest().subarray(0, 4);
}

/** Verifica el MAC de un documento `mesh_states` Versión=0x02 dado la clave
 * pública Ed25519 del dispositivo (de `participants`) y la clave privada
 * X25519 real del backend (Secret Manager). No lanza — cualquier campo
 * faltante o mal formado se trata como fallo de verificación (documento
 * señalado, nunca crashea la función ni bloquea la escritura). */
export function verifyCaseBMac({ meshState, devicePublicKeyEd25519Hex, backendPrivateKeyX25519Hex }) {
  try {
    const devicePublicKeyEd25519 = Buffer.from(devicePublicKeyEd25519Hex, 'hex');
    const backendPrivateKeyX25519 = Buffer.from(backendPrivateKeyX25519Hex, 'hex');
    const kShared = deriveKShared(backendPrivateKeyX25519, devicePublicKeyEd25519);
    const content = authenticatedContent({
      deviceIdHash: Buffer.from(meshState.device_id_hash, 'hex'),
      messageType: meshState.message_type,
      status: meshState.status,
      latitudeE7: meshState.latitude_e7,
      longitudeE7: meshState.longitude_e7,
      timestamp: meshState.beacon_timestamp,
      ttl: meshState.ttl,
      sequence: meshState.sequence,
    });
    const mac = computeMac(kShared, content);
    const expectedMac = Buffer.from(meshState.mac, 'hex');
    return mac.length === expectedMac.length && mac.equals(expectedMac);
  } catch (err) {
    // Cualquier campo faltante o mal formado (hex inválido, pubkey que no es
    // un punto válido de la curva, etc.) se trata igual que un MAC
    // incorrecto — nunca revienta la función que lo llama — pero se loguea
    // para no perder la distinción entre "MAC realmente falso" y "dato
    // corrupto" (mismo criterio que scripts/verify-dedup.mjs). `console.error`
    // en vez de `firebase-functions/logger` a propósito: este módulo es
    // puro, sin dependencias de Firebase, y Cloud Functions ya captura
    // stdout/stderr en Cloud Logging sin necesitar el SDK.
    console.error('verifyCaseBMac: no se pudo verificar', {
      deviceIdHash: meshState?.device_id_hash,
      reason: err?.message ?? String(err),
    });
    return false;
  }
}
