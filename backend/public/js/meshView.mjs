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

/** Agrega el nombre, contacto e identidad confirmada del
 * `participants/{device_id_hash}` correspondiente, sin mutar el estado
 * original. Si no hay participant registrado, o si registró nombre sin
 * contacto (ambos opcionales salvo el nombre, ver ADR-0003), los campos
 * faltantes quedan `null` — el hash se muestra crudo en su lugar (AC de
 * #33). `identityConfirmedCaseA` solo es `true` cuando el campo
 * `identidad_verificada_caso_a` del participant es exactamente `true`
 * (ausente o `false` cuentan como no confirmado, #54). */
export function attachParticipantInfo(meshState, participantsByHash) {
  const participant = participantsByHash[meshState.device_id_hash];
  return {
    ...meshState,
    name: participant ? participant.name : null,
    contact: participant?.contacto ?? null,
    identityConfirmedCaseA: participant?.identidad_verificada_caso_a === true,
  };
}

// `version` del layout Caso B (`spec/packet-format.md`) — mesh_states no
// declara este campo todavía (#48/#49 sin implementar), queda reservado acá
// para cuando esa ticket lo agregue.
const CASO_B_VERSION = 2;

/** Caso A = beacon del layout legado (`Versión=0x01`), la única forma que
 * `mesh_states` tiene hoy — ningún camino de código real sube todavía un
 * documento Caso B. Cualquier documento que no declare `version:
 * CASO_B_VERSION` se trata como Caso A (#54, "ningún beacon Caso B cambia
 * de comportamiento por este cambio"). */
export function isCasoA(meshState) {
  return meshState.version !== CASO_B_VERSION;
}

/** Qué mostrar en la columna "Verificación" para un beacon ya enriquecido
 * con `attachParticipantInfo` (#54). Caso A siempre lleva la marca
 * permanente de "no verificado" — la firma de Caso A solo autentica
 * identidad, nunca el contenido del beacon (límite aceptado,
 * `spec/packet-format.md` decisión 18) — con o sin la indicación adicional
 * de identidad confirmada. Caso B queda sin marca por este ticket; su
 * propio estado de verificación es responsabilidad de #49. */
export function verificationLabel(meshState) {
  if (!isCasoA(meshState)) return { unverified: false, identityConfirmed: false };
  return { unverified: true, identityConfirmed: meshState.identityConfirmedCaseA === true };
}

/** Orden de la vista de estado actual: más reciente primero. */
export function sortByMostRecent(meshStates) {
  return [...meshStates].sort((a, b) => b.uploaded_at - a.uploaded_at);
}
