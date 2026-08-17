// Lógica pura del Panel de rescate (#33) — sin dependencias de Firebase ni
// del DOM, para poder testear con `node --test` sin emulador. Se sirve tal
// cual desde Firebase Hosting (módulo ES nativo del navegador, sin paso de
// build) e importa igual desde los tests en backend/tests/.

/** Un documento por `device_id_hash`, quedándose con la mayor `sequence` —
 * misma regla "nueva Secuencia > vieja" que `MeshStateRegistry` en las apps
 * móviles (#31/#32). */
export function latestPerDevice(meshStates) {
  const latestByDevice = new Map();
  for (const state of meshStates) {
    const current = latestByDevice.get(state.device_id_hash);
    if (!current || state.sequence > current.sequence) {
      latestByDevice.set(state.device_id_hash, state);
    }
  }
  return Array.from(latestByDevice.values());
}

/** Todos los documentos de un `device_id_hash`, ordenados ascendente por
 * `sequence`. */
export function historyForDevice(meshStates, deviceIdHash) {
  return meshStates
    .filter((state) => state.device_id_hash === deviceIdHash)
    .sort((a, b) => a.sequence - b.sequence);
}

/** Agrega el nombre del `participants/{device_id_hash}` correspondiente,
 * sin mutar el estado original. Si no hay participant registrado, `name`
 * queda `null` — el hash se muestra crudo en su lugar (AC de #33). */
export function attachParticipantName(meshState, participantsByHash) {
  const participant = participantsByHash[meshState.device_id_hash];
  return { ...meshState, name: participant ? participant.name : null };
}

/** Orden de la vista de estado actual: más reciente primero. */
export function sortByMostRecent(meshStates) {
  return [...meshStates].sort((a, b) => b.uploaded_at - a.uploaded_at);
}
