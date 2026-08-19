// Verificación de proximidad de "resuelto" (#55/#59) — lógica pura, sin
// dependencias de Firebase/Firestore, para poder testear con `node --test`
// sin emulador (mismo principio que caseBVerification.mjs).

const EARTH_RADIUS_METERS = 6371000;
const PROXIMITY_RADIUS_METERS = 100;

function toRadians(degrees) {
  return (degrees * Math.PI) / 180;
}

export function haversineDistanceMeters(lat1, lon1, lat2, lon2) {
  const dLat = toRadians(lat2 - lat1);
  const dLon = toRadians(lon2 - lon1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRadians(lat1)) * Math.cos(toRadians(lat2)) * Math.sin(dLon / 2) ** 2;
  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return EARTH_RADIUS_METERS * c;
}

/** Última ubicación conocida de la víctima, normalizada a grados decimales.
 * Caso A (layout legado — `FirebaseMeshStateUploader`, iOS/Android) sube
 * `latitude`/`longitude` ya en decimal; Caso B (#42/#43, sin escritor real
 * todavía — #48) sube `latitude_e7`/`longitude_e7` como entero de punto
 * fijo. Se prueba `latitude`/`longitude` primero porque es el único formato
 * que existe en producción hoy. */
export function victimLocationDegrees(meshState) {
  if (typeof meshState?.latitude === 'number' && typeof meshState?.longitude === 'number') {
    return { latitude: meshState.latitude, longitude: meshState.longitude };
  }
  if (typeof meshState?.latitude_e7 === 'number' && typeof meshState?.longitude_e7 === 'number') {
    return { latitude: meshState.latitude_e7 / 1e7, longitude: meshState.longitude_e7 / 1e7 };
  }
  return undefined;
}

/** Ubicación del resolutor al marcar "resuelto" (#56) — siempre en punto
 * fijo e7, sin importar el Caso del beacon de la víctima. */
export function resolverLocationDegrees(meshState) {
  if (typeof meshState?.resolutor_latitud_e7 === 'number' && typeof meshState?.resolutor_longitud_e7 === 'number') {
    return { latitude: meshState.resolutor_latitud_e7 / 1e7, longitude: meshState.resolutor_longitud_e7 / 1e7 };
  }
  return undefined;
}

/** `true` si resolutor y víctima están a <=100m, `false` si más lejos,
 * `undefined` si falta alguna de las dos ubicaciones — nunca lanza, mismo
 * principio que `verifyCaseBMac` (#48): señalado, nunca bloqueado. */
export function computeProximityVerified(meshState) {
  const victim = victimLocationDegrees(meshState);
  const resolver = resolverLocationDegrees(meshState);
  if (!victim || !resolver) return undefined;
  const distance = haversineDistanceMeters(victim.latitude, victim.longitude, resolver.latitude, resolver.longitude);
  return distance <= PROXIMITY_RADIUS_METERS;
}

/** Si esta escritura debe procesarse: solo cuando "resuelto" está presente
 * y `proximidad_verificada` todavía no se calculó. La segunda condición es
 * la guarda contra el loop de re-disparo (#59) — la propia escritura de
 * vuelta de la función deja `proximidad_verificada` definido, así que la
 * invocación que ese mismo write dispara se ve a sí misma como "ya
 * procesada" y no hace nada. No aplica a "atendiendo" (#55: sin
 * proximidad) porque esas escrituras nunca traen `resuelto`. */
export function shouldComputeProximity(meshState) {
  return meshState?.resuelto === true && meshState?.proximidad_verificada === undefined;
}
