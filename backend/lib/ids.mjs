// Convención de IDs de documento (ver docs/adr/0002-dedup-multi-gateway-id-deterministico.md
// y docs/adr/0003-identidad-participantes-registro-opt-in.md). Única fuente de verdad para
// tests y scripts — firestore.rules repite la misma fórmula porque las reglas no pueden
// importar este módulo.

export function meshStateDocId(deviceIdHash, sequence) {
  return `${deviceIdHash}_${sequence}`;
}

export function participantDocId(deviceIdHash) {
  return deviceIdHash;
}
