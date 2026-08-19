// Lógica pura de la verificación de proximidad de "resuelto" (#55/#59) — sin
// dependencias de Firebase/Firestore, mismo principio que caseBVerification.mjs:
// la lógica de negocio vive separada del glue de infraestructura, testeable
// con `node --test` sin emulador.
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  haversineDistanceMeters,
  victimLocationDegrees,
  resolverLocationDegrees,
  computeProximityVerified,
  shouldComputeProximity,
} from '../functions/lib/resolutionProximity.mjs';

describe('haversineDistanceMeters', () => {
  it('devuelve 0 para el mismo punto', () => {
    assert.equal(haversineDistanceMeters(-12.05, -77.04, -12.05, -77.04), 0);
  });

  it('calcula ~111.2km para un grado de latitud (constante conocida)', () => {
    const distance = haversineDistanceMeters(0, 0, 1, 0);
    assert.ok(distance > 111000 && distance < 111400, `esperaba ~111.2km, dio ${distance}`);
  });
});

describe('victimLocationDegrees', () => {
  it('usa latitude/longitude decimal si están presentes (Caso A, FirebaseMeshStateUploader)', () => {
    const loc = victimLocationDegrees({ latitude: -12.05, longitude: -77.04 });
    assert.deepEqual(loc, { latitude: -12.05, longitude: -77.04 });
  });

  it('usa latitude_e7/longitude_e7 si latitude/longitude no están (Caso B, #42/#43)', () => {
    const loc = victimLocationDegrees({ latitude_e7: -120500000, longitude_e7: -770400000 });
    assert.deepEqual(loc, { latitude: -12.05, longitude: -77.04 });
  });

  it('prefiere latitude/longitude decimal si ambos formatos están presentes', () => {
    const loc = victimLocationDegrees({ latitude: -1, longitude: -1, latitude_e7: -120500000, longitude_e7: -770400000 });
    assert.deepEqual(loc, { latitude: -1, longitude: -1 });
  });

  it('devuelve undefined si no hay ninguna ubicación (documento parcial creado solo por la resolución)', () => {
    assert.equal(victimLocationDegrees({}), undefined);
  });
});

describe('resolverLocationDegrees', () => {
  it('convierte resolutor_latitud_e7/resolutor_longitud_e7 (siempre punto fijo e7, #56)', () => {
    const loc = resolverLocationDegrees({ resolutor_latitud_e7: -120500000, resolutor_longitud_e7: -770400000 });
    assert.deepEqual(loc, { latitude: -12.05, longitude: -77.04 });
  });

  it('devuelve undefined si el resolutor no tenía ubicación al marcar', () => {
    assert.equal(resolverLocationDegrees({}), undefined);
  });
});

describe('computeProximityVerified', () => {
  const victim = { latitude: -12.05, longitude: -77.04 };

  it('true cuando la distancia es <=100m', () => {
    // ~0.0005 grados de latitud ≈ 55.6m
    const meshState = { ...victim, resolutor_latitud_e7: -120505000, resolutor_longitud_e7: -770400000 };
    assert.equal(computeProximityVerified(meshState), true);
  });

  it('false cuando la distancia es >100m', () => {
    // ~0.002 grados de latitud ≈ 222.4m
    const meshState = { ...victim, resolutor_latitud_e7: -120520000, resolutor_longitud_e7: -770400000 };
    assert.equal(computeProximityVerified(meshState), false);
  });

  it('undefined cuando falta la ubicación del resolutor', () => {
    assert.equal(computeProximityVerified({ ...victim }), undefined);
  });

  it('undefined cuando falta la ubicación de la víctima (documento parcial)', () => {
    assert.equal(computeProximityVerified({ resolutor_latitud_e7: -120500000, resolutor_longitud_e7: -770400000 }), undefined);
  });
});

describe('shouldComputeProximity', () => {
  it('true cuando "resuelto" está presente y proximidad_verificada todavía no se calculó', () => {
    assert.equal(shouldComputeProximity({ resuelto: true }), true);
  });

  it('false cuando no hay "resuelto" (escritura de "atendiendo" u otra, sin proximidad — #55)', () => {
    assert.equal(shouldComputeProximity({ atendido_por: [{ device_id_hash: 'r1' }] }), false);
  });

  it('false cuando proximidad_verificada ya se calculó — evita el loop de re-disparo (#59)', () => {
    assert.equal(shouldComputeProximity({ resuelto: true, proximidad_verificada: true }), false);
    assert.equal(shouldComputeProximity({ resuelto: true, proximidad_verificada: false }), false);
    assert.equal(shouldComputeProximity({ resuelto: true, proximidad_verificada: null }), false);
  });

  it('false para un documento vacío o undefined', () => {
    assert.equal(shouldComputeProximity({}), false);
    assert.equal(shouldComputeProximity(undefined), false);
  });
});
