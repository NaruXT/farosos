#!/usr/bin/env node
// Corre esto UNA VEZ contra el proyecto real de Firebase, después de haber
// desplegado firestore.rules, para cumplir el criterio de aceptación de #28:
// "dos escrituras con el mismo {device_id_hash}_{sequence} no generan dos
// documentos". Usa el Admin SDK (bypassa las Security Rules), así que no
// hace falta ninguna sesión de Auth — solo credenciales de servicio.
//
// Uso:
//   GOOGLE_APPLICATION_CREDENTIALS=/ruta/a/service-account.json node scripts/verify-dedup.mjs

import { initializeApp, applicationDefault } from 'firebase-admin/app';
import { getFirestore, FieldValue } from 'firebase-admin/firestore';
import { meshStateDocId } from '../lib/ids.mjs';

const credentialsPath = process.env.GOOGLE_APPLICATION_CREDENTIALS;
if (!credentialsPath) {
  console.error(
    'Falta GOOGLE_APPLICATION_CREDENTIALS — apuntá a la clave de cuenta de servicio del proyecto Firebase real.'
  );
  process.exit(1);
}

initializeApp({ credential: applicationDefault() });
const db = getFirestore();

const DEVICE_ID_HASH = 'verify-dedup-smoke-test';
const SEQUENCE = 1;
const DOC_ID = meshStateDocId(DEVICE_ID_HASH, SEQUENCE);
const docRef = db.collection('mesh_states').doc(DOC_ID);

function samplePayload(gatewayId) {
  return {
    device_id_hash: DEVICE_ID_HASH,
    status: 'OK',
    latitude: 0,
    longitude: 0,
    beacon_timestamp: Math.floor(Date.now() / 1000),
    sequence: SEQUENCE,
    uploaded_at: Math.floor(Date.now() / 1000),
    confirmed_by_gateways: FieldValue.arrayUnion(gatewayId),
  };
}

async function main() {
  console.log(`Escribiendo dos veces el documento ${DOC_ID}...`);

  await docRef.set(samplePayload('gateway-a'));
  await docRef.set(samplePayload('gateway-b'), { merge: true });

  const matching = await db
    .collection('mesh_states')
    .where('device_id_hash', '==', DEVICE_ID_HASH)
    .where('sequence', '==', SEQUENCE)
    .get();

  await docRef.delete();

  if (matching.size !== 1) {
    console.error(
      `✖ Se esperaba 1 documento para (${DEVICE_ID_HASH}, ${SEQUENCE}), se encontraron ${matching.size}.`
    );
    process.exit(1);
  }

  const confirmedBy = matching.docs[0].get('confirmed_by_gateways');
  console.log(`✔ Un único documento para (persona, secuencia). confirmed_by_gateways: ${confirmedBy}`);
}

main().catch((err) => {
  console.error('Falló la verificación de dedup:', err);
  process.exit(1);
});
