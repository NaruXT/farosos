import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  latestPerDevice,
  historyForDevice,
  attachParticipantInfo,
  isCasoA,
  verificationLabel,
  statusLabel,
  sortByMostRecent,
} from '../public/js/meshView.mjs';

function state(overrides = {}) {
  return {
    device_id_hash: 'abc123',
    status: 'OK',
    latitude: -12.05,
    longitude: -77.04,
    beacon_timestamp: 1755000000,
    sequence: 0,
    uploaded_at: 1755000010,
    confirmed_by_gateways: ['gw1'],
    ...overrides,
  };
}

describe('latestPerDevice', () => {
  it('devuelve vacío si no hay documentos', () => {
    assert.deepEqual(latestPerDevice([]), []);
  });

  it('devuelve el único documento de un dispositivo con una sola secuencia', () => {
    const only = state({ sequence: 0 });
    assert.deepEqual(latestPerDevice([only]), [only]);
  });

  it('se queda con la secuencia más alta de un mismo dispositivo, sin importar el orden de entrada', () => {
    const s0 = state({ sequence: 0, status: 'SIN_CONFIRMAR' });
    const s2 = state({ sequence: 2, status: 'AYUDA' });
    const s1 = state({ sequence: 1, status: 'OK' });

    assert.deepEqual(latestPerDevice([s0, s2, s1]), [s2]);
  });

  it('devuelve un documento por cada dispositivo distinto', () => {
    const deviceA = state({ device_id_hash: 'aaa', sequence: 3 });
    const deviceB = state({ device_id_hash: 'bbb', sequence: 1 });

    const result = latestPerDevice([deviceA, deviceB]);

    assert.equal(result.length, 2);
    assert.deepEqual(
      new Set(result.map((r) => r.device_id_hash)),
      new Set(['aaa', 'bbb'])
    );
  });
});

describe('historyForDevice', () => {
  it('devuelve vacío si el dispositivo no tiene documentos', () => {
    assert.deepEqual(historyForDevice([state({ device_id_hash: 'aaa' })], 'zzz'), []);
  });

  it('filtra solo los documentos del dispositivo pedido', () => {
    const mine = state({ device_id_hash: 'aaa', sequence: 0 });
    const other = state({ device_id_hash: 'bbb', sequence: 0 });

    assert.deepEqual(historyForDevice([mine, other], 'aaa'), [mine]);
  });

  it('ordena ascendente por secuencia, sin importar el orden de entrada', () => {
    const s2 = state({ sequence: 2 });
    const s0 = state({ sequence: 0 });
    const s1 = state({ sequence: 1 });

    assert.deepEqual(historyForDevice([s2, s0, s1], 'abc123'), [s0, s1, s2]);
  });
});

describe('attachParticipantInfo', () => {
  it('agrega nombre y contacto cuando existe un participant con ambos campos', () => {
    const result = attachParticipantInfo(state({ device_id_hash: 'aaa' }), {
      aaa: { name: 'Ana', contacto: '+51999999999' },
    });
    assert.equal(result.name, 'Ana');
    assert.equal(result.contact, '+51999999999');
  });

  it('tolera un participant con nombre pero sin contacto (contacto es opcional, ADR-0003) — contact queda null', () => {
    const result = attachParticipantInfo(state({ device_id_hash: 'aaa' }), { aaa: { name: 'Ana' } });
    assert.equal(result.name, 'Ana');
    assert.equal(result.contact, null);
  });

  it('tolera que no exista participant para ese device_id_hash — name y contact quedan null', () => {
    const result = attachParticipantInfo(state({ device_id_hash: 'zzz' }), { aaa: { name: 'Ana' } });
    assert.equal(result.name, null);
    assert.equal(result.contact, null);
  });

  it('tolera un mapa de participants vacío', () => {
    const result = attachParticipantInfo(state({ device_id_hash: 'aaa' }), {});
    assert.equal(result.name, null);
    assert.equal(result.contact, null);
  });

  it('no muta el estado original', () => {
    const original = state({ device_id_hash: 'aaa' });
    attachParticipantInfo(original, { aaa: { name: 'Ana', contacto: '+51999999999' } });
    assert.equal(original.name, undefined);
    assert.equal(original.contact, undefined);
  });

  it('identityConfirmedCaseA es true cuando el participant tiene identidad_verificada_caso_a: true (#54)', () => {
    const result = attachParticipantInfo(state({ device_id_hash: 'aaa' }), {
      aaa: { identidad_verificada_caso_a: true },
    });
    assert.equal(result.identityConfirmedCaseA, true);
  });

  it('identityConfirmedCaseA es false cuando el participant tiene identidad_verificada_caso_a: false', () => {
    const result = attachParticipantInfo(state({ device_id_hash: 'aaa' }), {
      aaa: { identidad_verificada_caso_a: false },
    });
    assert.equal(result.identityConfirmedCaseA, false);
  });

  it('identityConfirmedCaseA es false cuando el campo está ausente (nunca se confirmó, o no hay participant)', () => {
    const withParticipantSinCampo = attachParticipantInfo(state({ device_id_hash: 'aaa' }), { aaa: { name: 'Ana' } });
    const sinParticipant = attachParticipantInfo(state({ device_id_hash: 'zzz' }), { aaa: { name: 'Ana' } });
    assert.equal(withParticipantSinCampo.identityConfirmedCaseA, false);
    assert.equal(sinParticipant.identityConfirmedCaseA, false);
  });
});

describe('isCasoA', () => {
  it('es true cuando el documento no declara version (100% de mesh_states hoy, #54)', () => {
    assert.equal(isCasoA(state()), true);
  });

  it('es true cuando version es 1 (legado, explícito)', () => {
    assert.equal(isCasoA(state({ version: 1 })), true);
  });

  it('es false cuando version es 2 (Caso B — reservado para cuando #48/#49 exista)', () => {
    assert.equal(isCasoA(state({ version: 2 })), false);
  });
});

describe('verificationLabel', () => {
  it('Caso A sin identidad confirmada: unverified true, verified false, identityConfirmed false', () => {
    const enriched = attachParticipantInfo(state(), {});
    assert.deepEqual(verificationLabel(enriched), { unverified: true, verified: false, identityConfirmed: false });
  });

  it('Caso A con identidad confirmada: unverified true, verified false, identityConfirmed true', () => {
    const enriched = attachParticipantInfo(state({ device_id_hash: 'aaa' }), {
      aaa: { identidad_verificada_caso_a: true },
    });
    assert.deepEqual(verificationLabel(enriched), { unverified: true, verified: false, identityConfirmed: true });
  });

  it('Caso B con MAC válido (#48 lo marcó mac_verificado: true): unverified false, verified true', () => {
    const enriched = attachParticipantInfo(state({ version: 2, mac_verificado: true }), {});
    assert.deepEqual(verificationLabel(enriched), { unverified: false, verified: true, identityConfirmed: false });
  });

  it('Caso B con MAC inválido (#48 lo marcó mac_verificado: false): unverified true, verified false — nunca se filtra (#49)', () => {
    const enriched = attachParticipantInfo(state({ version: 2, mac_verificado: false }), {});
    assert.deepEqual(verificationLabel(enriched), { unverified: true, verified: false, identityConfirmed: false });
  });

  it('Caso B sin mac_verificado todavía (la función de #48 no llegó a procesarlo) se trata como no verificado, no como verificado por defecto', () => {
    const enriched = attachParticipantInfo(state({ version: 2 }), {});
    assert.deepEqual(verificationLabel(enriched), { unverified: true, verified: false, identityConfirmed: false });
  });
});

// Caso A ya escribe `status` como string ('OK', 'AYUDA', ...) — la Cloud
// Function de #48 escribe el `status` de Caso B como el entero crudo del
// wire (0-4, necesario para recalcular el MAC). Sin esto, un beacon Caso B
// real rompe `statusCell` completo (`status.toLowerCase is not a
// function`), lo que oculta TODA la tabla — descubierto verificando #49 en
// vivo contra el panel real, viola el AC "nunca se filtra de la lista".
describe('statusLabel', () => {
  it('Caso A: status ya es el string, se devuelve tal cual', () => {
    assert.equal(statusLabel(state({ status: 'AYUDA' })), 'AYUDA');
  });

  it('Caso B: status es el entero crudo del wire, se traduce al mismo string que usa Caso A', () => {
    assert.equal(statusLabel(state({ version: 2, status: 2 })), 'AYUDA');
    assert.equal(statusLabel(state({ version: 2, status: 0 })), 'SIN_CONFIRMAR');
    assert.equal(statusLabel(state({ version: 2, status: 1 })), 'OK');
    assert.equal(statusLabel(state({ version: 2, status: 3 })), 'SILENCIO_TIMEOUT');
    assert.equal(statusLabel(state({ version: 2, status: 4 })), 'GATEWAY_DISPONIBLE');
  });

  it('un código numérico fuera de rango no revienta — se devuelve como string crudo', () => {
    assert.equal(statusLabel(state({ version: 2, status: 99 })), '99');
  });
});

describe('sortByMostRecent', () => {
  it('ordena descendente por uploaded_at, sin importar el orden de entrada', () => {
    const oldest = state({ device_id_hash: 'aaa', uploaded_at: 100 });
    const newest = state({ device_id_hash: 'bbb', uploaded_at: 300 });
    const middle = state({ device_id_hash: 'ccc', uploaded_at: 200 });

    assert.deepEqual(sortByMostRecent([oldest, newest, middle]), [newest, middle, oldest]);
  });

  it('no muta el array original', () => {
    const original = [state({ uploaded_at: 100 }), state({ uploaded_at: 200 })];
    const copy = [...original];
    sortByMostRecent(original);
    assert.deepEqual(original, copy);
  });
});
