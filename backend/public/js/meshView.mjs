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

// `version` del layout Caso B (`spec/packet-format.md`).
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
 * de identidad confirmada; `verified` siempre es `false` para Caso A, sin
 * excepción. Caso B refleja `mac_verificado`, el campo que escribe la
 * Cloud Function de #48 — comparación estricta contra `true` (#49): si
 * todavía no llegó a procesarlo (campo ausente) se trata como no
 * verificado, nunca como verificado por defecto. Un Caso B sin verificar
 * nunca se oculta ni se distingue de "no existe" — sigue apareciendo en la
 * lista, con la marca puesta acá. */
export function verificationLabel(meshState) {
  if (isCasoA(meshState)) {
    return { unverified: true, verified: false, identityConfirmed: meshState.identityConfirmedCaseA === true };
  }
  const verified = meshState.mac_verificado === true;
  return { unverified: !verified, verified, identityConfirmed: false };
}

// `Estado` del layout legado (`spec/packet-format.md`) — mismos nombres que
// ya escribe `FirebaseMeshStateUploader`/`FirebaseMeshStateUploader.kt` para
// Caso A (string). La Cloud Function de #48 escribe el `status` de Caso B
// como el entero crudo del wire (0-4), porque eso es lo que necesita para
// recalcular el MAC — `statusLabel` traduce ambas formas a la misma etiqueta
// para que el resto del panel no tenga que distinguir Caso A de Caso B.
const STATUS_LABEL_BY_CODE = {
  0: 'SIN_CONFIRMAR',
  1: 'OK',
  2: 'AYUDA',
  3: 'SILENCIO_TIMEOUT',
  4: 'GATEWAY_DISPONIBLE',
};

/** `status` listo para mostrar, sin importar si el documento es Caso A
 * (ya viene como string) o Caso B (entero crudo del wire, #48). Un código
 * numérico desconocido se devuelve como string en vez de reventar. */
export function statusLabel(meshState) {
  const { status } = meshState;
  if (typeof status !== 'number') return status;
  return STATUS_LABEL_BY_CODE[status] ?? String(status);
}

/** Orden de la vista de estado actual: más reciente primero. */
export function sortByMostRecent(meshStates) {
  return [...meshStates].sort((a, b) => b.uploaded_at - a.uploaded_at);
}

/** Traduce el campo crudo `proximidad_verificada` (`true`/`false`/ausente,
 * escrito por la Cloud Function de #59) a una de las tres etiquetas que
 * pide #55/#60 — nunca se trata como "verificada" por defecto cuando la
 * función todavía no llegó a procesar el caso (mismo principio que
 * `verificationLabel` con `mac_verificado`). */
export function proximityLabel(proximidadVerificada) {
  if (proximidadVerificada === true) return 'verificada';
  if (proximidadVerificada === false) return 'fuera_de_rango';
  return 'sin_verificar';
}

/** Estado de "resuelto" (#55) de un `mesh_states` — no resuelto si el campo
 * `resuelto` no es exactamente `true` (ausente o `false` cuentan igual).
 * Cuando sí lo está, expone quién lo marcó, cuándo, y la proximidad — nunca
 * oculta el caso por ninguno de los tres valores posibles de
 * `proximidad_verificada` (AC de #60). Funciona igual sobre un documento
 * parcial (creado solo por la resolución, sin campos de beacon todavía). */
export function resolutionInfo(meshState) {
  if (meshState.resuelto !== true) return { resolved: false };
  return {
    resolved: true,
    resolvedBy: meshState.resuelto_por ?? null,
    resolvedAt: meshState.resuelto_en ?? null,
    proximity: proximityLabel(meshState.proximidad_verificada),
  };
}

/** Lista completa de participantes que marcaron "atendiendo" (#55) sobre
 * este caso — nunca solo el primero. Vacía si el campo está ausente
 * (nadie lo marcó todavía). Cada entrada trae `device_id_hash`/`marcado_en`
 * (mismo esquema que escriben los clientes iOS/Android de #57/#58). */
export function attendingList(meshState) {
  return meshState.atendido_por ?? [];
}

/** Si el documento tiene los campos del beacon original (`status`, en
 * particular) — falso para un documento creado únicamente por una
 * resolución/atendiendo que llegó antes que el beacon real de la víctima
 * (#55/#60, "documento parcial"). El resto del panel usa esto para no
 * intentar mostrar estado/ubicación/hora de subida que no existen
 * todavía, sin romper la fila ni el resto de la tabla. */
export function hasBeaconData(meshState) {
  return typeof meshState.status !== 'undefined';
}
