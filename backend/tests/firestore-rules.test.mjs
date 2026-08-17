import { before, after, beforeEach, describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  initializeTestEnvironment,
  assertSucceeds,
  assertFails,
} from '@firebase/rules-unit-testing';
import { doc, setDoc, getDoc, getDocs, collection } from 'firebase/firestore';
import { meshStateDocId, participantDocId } from '../lib/ids.mjs';

let testEnv;

before(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: 'farosos-rules-test',
    firestore: {
      rules: readFileSync(new URL('../firestore.rules', import.meta.url), 'utf8'),
      host: '127.0.0.1',
      port: 8080,
    },
  });
});

after(async () => {
  await testEnv.cleanup();
});

beforeEach(async () => {
  await testEnv.clearFirestore();
});

function buildMeshState(overrides = {}) {
  return {
    device_id_hash: 'abc123',
    status: 'AYUDA',
    latitude: -12.05,
    longitude: -77.04,
    beacon_timestamp: 1755000000,
    sequence: 3,
    uploaded_at: 1755000010,
    confirmed_by_gateways: ['gw1'],
    ...overrides,
  };
}

function buildParticipant(overrides = {}) {
  return {
    device_id_hash: 'abc123',
    name: 'Ana',
    contacto: '+51999999999',
    ...overrides,
  };
}

function anonContext(uid) {
  return testEnv.authenticatedContext(uid, {
    firebase: { sign_in_provider: 'anonymous' },
  });
}

function passwordContext(uid) {
  return testEnv.authenticatedContext(uid, {
    firebase: { sign_in_provider: 'password' },
  });
}

const COLLECTIONS = [
  {
    name: 'mesh_states',
    build: buildMeshState,
    docId: (data) => meshStateDocId(data.device_id_hash, data.sequence),
  },
  {
    name: 'participants',
    build: buildParticipant,
    docId: (data) => participantDocId(data.device_id_hash),
  },
];

for (const { name: collectionName, build, docId } of COLLECTIONS) {
  describe(`${collectionName} — reglas de seguridad`, () => {
    it('deniega lectura sin autenticación', async () => {
      const unauth = testEnv.unauthenticatedContext();
      const sample = build();
      await assertFails(getDoc(doc(unauth.firestore(), collectionName, docId(sample))));
    });

    it('deniega escritura sin autenticación', async () => {
      const unauth = testEnv.unauthenticatedContext();
      const sample = build();
      await assertFails(setDoc(doc(unauth.firestore(), collectionName, docId(sample)), sample));
    });

    it('permite escritura con sesión anónima (teléfono de la app), con el docId correcto', async () => {
      const anon = anonContext('gateway-phone-1');
      const sample = build();
      await assertSucceeds(setDoc(doc(anon.firestore(), collectionName, docId(sample)), sample));
    });

    it('deniega escritura con sesión anónima si el docId no sigue la convención determinística', async () => {
      const anon = anonContext('gateway-phone-1');
      const sample = build();
      await assertFails(setDoc(doc(anon.firestore(), collectionName, 'un-id-cualquiera'), sample));
    });

    it('deniega lectura con sesión anónima', async () => {
      const anon = anonContext('gateway-phone-1');
      const sample = build();
      await assertFails(getDoc(doc(anon.firestore(), collectionName, docId(sample))));
    });

    it('permite escritura con la credencial compartida (password), con el docId correcto', async () => {
      const dashboard = passwordContext('rescue-dashboard');
      const sample = build();
      await assertSucceeds(setDoc(doc(dashboard.firestore(), collectionName, docId(sample)), sample));
    });

    it('permite lectura con la credencial compartida (password)', async () => {
      const sample = build();
      await testEnv.withSecurityRulesDisabled(async (ctx) => {
        await setDoc(doc(ctx.firestore(), collectionName, docId(sample)), sample);
      });
      const dashboard = passwordContext('rescue-dashboard');
      await assertSucceeds(getDoc(doc(dashboard.firestore(), collectionName, docId(sample))));
    });
  });
}

describe('lectura de colección completa (Panel de rescate, #33)', () => {
  it('permite listar toda la colección mesh_states con la credencial compartida', async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'mesh_states', meshStateDocId('aaa', 0)), buildMeshState({ device_id_hash: 'aaa', sequence: 0 }));
      await setDoc(doc(ctx.firestore(), 'mesh_states', meshStateDocId('bbb', 0)), buildMeshState({ device_id_hash: 'bbb', sequence: 0 }));
    });

    const dashboard = passwordContext('rescue-dashboard');
    const snapshot = await assertSucceeds(getDocs(collection(dashboard.firestore(), 'mesh_states')));
    assert.equal(snapshot.size, 2);
  });

  it('deniega listar toda la colección mesh_states con sesión anónima', async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), 'mesh_states', meshStateDocId('aaa', 0)), buildMeshState({ device_id_hash: 'aaa', sequence: 0 }));
    });

    const anon = anonContext('gateway-phone-1');
    await assertFails(getDocs(collection(anon.firestore(), 'mesh_states')));
  });
});

describe('deduplicación por ID determinístico ({device_id_hash}_{sequence})', () => {
  it('dos gateways subiendo la misma (persona, secuencia) no generan dos documentos', async () => {
    const sample = buildMeshState();
    const id = meshStateDocId(sample.device_id_hash, sample.sequence);

    await assertSucceeds(
      setDoc(doc(anonContext('gateway-phone-1').firestore(), 'mesh_states', id), {
        ...sample,
        confirmed_by_gateways: ['gateway-phone-1'],
      })
    );
    await assertSucceeds(
      setDoc(doc(anonContext('gateway-phone-2').firestore(), 'mesh_states', id), {
        ...sample,
        confirmed_by_gateways: ['gateway-phone-1', 'gateway-phone-2'],
      })
    );

    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      const snap = await getDocs(collection(ctx.firestore(), 'mesh_states'));
      assert.equal(snap.size, 1, 'debe existir un único documento para (persona, secuencia)');
    });
  });
});
