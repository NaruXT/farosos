import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash, createHmac, createPrivateKey, createPublicKey, diffieHellman } from 'node:crypto';

// Verifica spec/test-vectors.json (secciones case_b, device_id_hash_vectors,
// ecdh, mac_vectors — #39) con una reimplementación independiente del
// generador (backend/scripts/generate-beacon-auth-vectors.mjs): usa el
// crypto nativo de Node (HMAC/SHA-256/X25519) en vez de @noble/curves, para
// detectar un error del generador o una edición manual del JSON que rompa
// la consistencia interna de los vectores.

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const vectors = JSON.parse(readFileSync(path.join(repoRoot, 'spec', 'test-vectors.json'), 'utf8'));

function hex(buf) {
  return Buffer.from(buf).toString('hex');
}

function b64url(hexStr) {
  return Buffer.from(hexStr, 'hex').toString('base64url');
}

function leSigned32(n) {
  const buf = Buffer.alloc(4);
  buf.writeInt32LE(n, 0);
  return buf;
}

function leUnsigned32(n) {
  const buf = Buffer.alloc(4);
  buf.writeUIntLE(n >>> 0, 0, 4);
  return buf;
}

function authenticatedContent(fields) {
  const tipoEstado = ((fields.message_type & 0x0f) << 4) | (fields.status & 0x0f);
  return Buffer.concat([
    Buffer.from(fields.device_id_hash, 'hex'),
    Buffer.from([tipoEstado]),
    leSigned32(fields.latitude_e7),
    leSigned32(fields.longitude_e7),
    leUnsigned32(fields.timestamp),
    Buffer.from([fields.ttl]),
    Buffer.from([fields.sequence]),
  ]);
}

function encodeCaseB(fields) {
  const tipoEstado = ((fields.message_type & 0x0f) << 4) | (fields.status & 0x0f);
  return Buffer.concat([
    Buffer.from([0xe7]),
    Buffer.from([0x02]),
    Buffer.from([tipoEstado]),
    Buffer.from(fields.device_id_hash, 'hex'),
    leSigned32(fields.latitude_e7),
    leSigned32(fields.longitude_e7),
    leUnsigned32(fields.timestamp),
    Buffer.from([fields.ttl]),
    Buffer.from(fields.mac, 'hex'),
    Buffer.from([fields.sequence]),
  ]);
}

describe('spec/test-vectors.json — Caso B (#39)', () => {
  it('el layout legado (vectors) sigue intacto y sin tocar', () => {
    assert.equal(vectors.packet_size_bytes, 26);
    assert.ok(Array.isArray(vectors.vectors) && vectors.vectors.length > 0);
  });

  it('case_b declara 27 bytes y trae al menos un vector por cada Estado 0-4 más un caso límite', () => {
    assert.equal(vectors.case_b.version, 2);
    assert.equal(vectors.case_b.packet_size_bytes, 27);
    const statuses = new Set(vectors.case_b.vectors.map((v) => v.fields.status));
    for (const s of [0, 1, 2, 3, 4]) assert.ok(statuses.has(s), `falta un vector con status=${s}`);
    assert.ok(vectors.case_b.vectors.length >= 6);
  });

  for (const vector of vectors.case_b.vectors) {
    it(`caso_b bytes_hex reconstruye para "${vector.name}"`, () => {
      const encoded = encodeCaseB(vector.fields);
      assert.equal(hex(encoded), vector.bytes_hex, vector.name);
      assert.equal(encoded.length, 27, vector.name);
    });

    it(`caso_b content_hex (campos autenticados) reconstruye para "${vector.name}"`, () => {
      const content = authenticatedContent(vector.fields);
      assert.equal(hex(content), vector.content_hex, vector.name);
      assert.equal(content.length, 21, vector.name);
    });
  }

  it('device_id_hash se deriva de SHA-256(pubkey Ed25519)[:6]', () => {
    for (const v of vectors.device_id_hash_vectors) {
      const expected = createHash('sha256')
        .update(Buffer.from(v.public_key_ed25519_hex, 'hex'))
        .digest()
        .subarray(0, 6);
      assert.equal(hex(expected), v.device_id_hash, v.name);
    }
  });

  it('ECDH: K_shared coincide usando el X25519 nativo de Node (independiente de @noble/curves)', () => {
    for (const v of vectors.ecdh.vectors) {
      assert.equal(v.backend_public_key_x25519_hex, vectors.ecdh.backend_public_key_x25519_hex, v.name);

      const devicePrivKeyObj = createPrivateKey({
        key: { kty: 'OKP', crv: 'X25519', d: b64url(v.device_secret_key_x25519_hex), x: b64url(v.device_public_key_x25519_hex) },
        format: 'jwk',
      });
      const backendPubKeyObj = createPublicKey({
        key: { kty: 'OKP', crv: 'X25519', x: b64url(v.backend_public_key_x25519_hex) },
        format: 'jwk',
      });
      const shared = diffieHellman({ privateKey: devicePrivKeyObj, publicKey: backendPubKeyObj });
      assert.equal(hex(shared), v.expected_k_shared_hex, v.name);
    }
  });

  it('MAC = HMAC-SHA256(K_shared, content)[:4] para cada mac_vector', () => {
    for (const v of vectors.mac_vectors) {
      const mac = createHmac('sha256', Buffer.from(v.k_shared_hex, 'hex'))
        .update(Buffer.from(v.content_hex, 'hex'))
        .digest()
        .subarray(0, 4);
      assert.equal(hex(mac), v.expected_mac_hex, v.name);
    }
  });

  it('cada mac_vector coincide con el MAC embebido en su case_b vector correspondiente', () => {
    const byName = new Map(vectors.case_b.vectors.map((v) => [v.name, v]));
    for (const m of vectors.mac_vectors) {
      const caseBName = m.name.replace(/^mac_/, '');
      const caseBVector = byName.get(caseBName);
      assert.ok(caseBVector, `no hay case_b vector para ${m.name}`);
      assert.equal(m.expected_mac_hex, caseBVector.fields.mac, m.name);
    }
  });
});
