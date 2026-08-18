import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash, createPublicKey, sign as cryptoSign, verify as cryptoVerify } from 'node:crypto';

// Verifica spec/test-vectors.json (clave `fragmento_firma`, #38/#44) con una
// reimplementación independiente del generador
// (backend/scripts/generate-case-a-signature-vectors.mjs): usa el Ed25519
// nativo de Node en vez de @noble/curves, para detectar un error del
// generador o una edición manual del JSON que rompa la consistencia interna
// de los vectores.

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const vectors = JSON.parse(readFileSync(path.join(repoRoot, 'spec', 'test-vectors.json'), 'utf8'));
const fragmentoFirma = vectors.fragmento_firma;

function hex(buf) {
  return Buffer.from(buf).toString('hex');
}

function b64url(hexStr) {
  return Buffer.from(hexStr, 'hex').toString('base64url');
}

function fragHeaderByte(index, count) {
  return ((index & 0x0f) << 4) | (count & 0x0f);
}

function encodeFragment(fields) {
  const chunk = Buffer.from(fields.chunk_hex, 'hex');
  const padded = Buffer.alloc(fragmentoFirma.payload_chunk_size_bytes);
  chunk.copy(padded);
  return Buffer.concat([
    Buffer.from([0xe7]),
    Buffer.from([fragmentoFirma.version]),
    Buffer.from([fragmentoFirma.message_type]),
    Buffer.from(fields.device_id_hash, 'hex'),
    Buffer.from([fields.ttl]),
    Buffer.from([fragHeaderByte(fields.frag_index, fields.frag_count)]),
    padded,
  ]);
}

describe('spec/test-vectors.json — Caso A, fragmentación de firma (#44)', () => {
  it('declara 26 bytes de paquete, Tipo=3, 7 fragmentos de 15 bytes', () => {
    assert.equal(fragmentoFirma.version, 1);
    assert.equal(fragmentoFirma.message_type, 3);
    assert.equal(fragmentoFirma.packet_size_bytes, 26);
    assert.equal(fragmentoFirma.payload_chunk_size_bytes, 15);
    assert.equal(fragmentoFirma.fragment_count, 7);
    assert.equal(fragmentoFirma.fragments.length, 7);
  });

  it('device_id_hash = SHA-256(pubkey Ed25519)[:6]', () => {
    const expected = createHash('sha256')
      .update(Buffer.from(fragmentoFirma.identity.device_public_key_ed25519_hex, 'hex'))
      .digest()
      .subarray(0, 6);
    assert.equal(hex(expected), fragmentoFirma.identity.device_id_hash);
  });

  it('la firma es un autocertificado válido: Ed25519_Verify(pubkey, firma, mensaje=pubkey) con crypto nativo de Node', () => {
    const publicKeyObj = createPublicKey({
      key: { kty: 'OKP', crv: 'Ed25519', x: b64url(fragmentoFirma.identity.device_public_key_ed25519_hex) },
      format: 'jwk',
    });
    const message = Buffer.from(fragmentoFirma.identity.device_public_key_ed25519_hex, 'hex');
    const signature = Buffer.from(fragmentoFirma.identity.signature_hex, 'hex');
    assert.equal(signature.length, 64);
    assert.ok(cryptoVerify(null, message, publicKeyObj, signature), 'la firma debe verificar contra su propia pubkey');
  });

  it('payload_hex = pubkey (32B) || firma (64B) = 96 bytes', () => {
    const expected = fragmentoFirma.identity.device_public_key_ed25519_hex + fragmentoFirma.identity.signature_hex;
    assert.equal(fragmentoFirma.payload_hex, expected);
    assert.equal(Buffer.from(fragmentoFirma.payload_hex, 'hex').length, 96);
  });

  it('concatenar chunk_hex de los 7 fragmentos en orden reconstruye payload_hex exacto', () => {
    const reconstructed = fragmentoFirma.fragments
      .slice()
      .sort((a, b) => a.fields.frag_index - b.fields.frag_index)
      .map((f) => f.fields.chunk_hex)
      .join('');
    assert.equal(reconstructed, fragmentoFirma.payload_hex);
  });

  it('el último fragmento (índice 6) trae solo 6 bytes reales, el resto 15', () => {
    for (const f of fragmentoFirma.fragments) {
      const expectedLen = f.fields.frag_index === 6 ? 6 : 15;
      assert.equal(f.fields.chunk_len, expectedLen, f.name);
      assert.equal(Buffer.from(f.fields.chunk_hex, 'hex').length, expectedLen, f.name);
    }
  });

  for (const fragment of fragmentoFirma.fragments) {
    it(`fragmento "${fragment.name}" bytes_hex reconstruye (incluido el relleno de ceros del payload)`, () => {
      const encoded = encodeFragment(fragment.fields);
      assert.equal(hex(encoded), fragment.bytes_hex, fragment.name);
      assert.equal(encoded.length, 26, fragment.name);
    });
  }
});
