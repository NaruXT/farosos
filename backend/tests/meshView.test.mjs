import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { latestPerDevice, historyForDevice, attachParticipantInfo, sortByMostRecent } from '../public/js/meshView.mjs';

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
