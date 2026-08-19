// Helpers compartidos entre los archivos de integración real de Cloud
// Functions (`caseBVerificationFunction.test.mjs` #48,
// `resolutionProximityFunction.test.mjs` #59) — ambos escriben documentos
// sintéticos directo en el emulador de Firestore vía Admin SDK y esperan a
// que la función real (corriendo en el emulador de Functions) los procese
// sola, sin invocar el handler a mano. No termina en `.test.mjs` a
// propósito, para que `node --test tests/*.test.mjs` no lo levante como
// archivo de test.

export async function waitForField(docRef, field, { timeoutMs = 10000, intervalMs = 200 } = {}) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    const snap = await docRef.get();
    if (snap.get(field) !== undefined) return snap.get(field);
    await new Promise((resolve) => setTimeout(resolve, intervalMs));
  }
  throw new Error(`timeout esperando el campo "${field}" en ${docRef.path}`);
}

/** Cada archivo de este tipo escribe en `mesh_states`/`participants` y
 * necesita limpiar entre tests — pero nunca borrando la colección completa:
 * los distintos archivos de `node --test tests/*.test.mjs` corren en
 * paralelo contra el mismo emulador, así que un borrado masivo de un
 * archivo puede pisar un documento que otro archivo está esperando a mitad
 * de vuelo (encontrado como fallas intermitentes reales al implementar
 * #59, no ruido de entorno). Cada instancia de este tracker borra solo lo
 * que ella misma creó. */
export function createTrackedWriter() {
  let refs = [];
  return {
    async set(docRef, data) {
      refs.push(docRef);
      await docRef.set(data);
      return docRef;
    },
    async cleanup() {
      await Promise.all(refs.map((ref) => ref.delete()));
      refs = [];
    },
  };
}
