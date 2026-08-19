// Primera Cloud Function del proyecto (#48) — hasta ahora `backend/` era
// solo hosting estático + reglas de Firestore + scripts puntuales con
// credenciales de admin, sin ningún compute backend desplegado.
//
// Se dispara al crearse un documento en `mesh_states` (donde ya aterrizan
// los beacons relayados por el rol de gateway, #31/#32). Solo procesa
// documentos `version: 2` (Caso B, #38/#42/#43) — `version` ausente o
// distinto de 2 es el layout legado (Caso A, GATEWAY_ANNOUNCE,
// ACK_RECEIVED), que no pasa por esta función y sigue exactamente igual que
// hoy. `onDocumentCreated` (no `onDocumentWritten`) es deliberado: la
// función escribe de vuelta sobre el mismo documento (`mac_verificado`), y
// usar un trigger de "creación únicamente" evita que esa escritura
// dispare la función de nuevo (un trigger de "cualquier escritura" se
// re-invocaría a sí mismo en un loop). El contenido autenticado de un
// beacon no cambia entre confirmaciones de distintos gateways sobre el
// mismo documento (ADR-0002, `confirmed_by_gateways`) — verificar una sola
// vez, en la creación, es correcto y evita trabajo redundante.
import { initializeApp } from 'firebase-admin/app';
import { getFirestore } from 'firebase-admin/firestore';
import { defineSecret } from 'firebase-functions/params';
import { onDocumentCreated, onDocumentWritten } from 'firebase-functions/v2/firestore';
import { verifyCaseBMac } from './lib/caseBVerification.mjs';
import { computeProximityVerified, shouldComputeProximity } from './lib/resolutionProximity.mjs';

const backendEcdhPrivateKeyX25519Hex = defineSecret('BACKEND_ECDH_PRIVATE_KEY_X25519_HEX');

initializeApp();

const CASO_B_VERSION = 2;

export const verifyCaseBMeshState = onDocumentCreated(
  { document: 'mesh_states/{docId}', secrets: [backendEcdhPrivateKeyX25519Hex] },
  async (event) => {
    const snapshot = event.data;
    if (!snapshot) return;

    const meshState = snapshot.data();
    if (meshState.version !== CASO_B_VERSION) return;

    const participantDoc = await getFirestore().collection('participants').doc(meshState.device_id_hash).get();
    const devicePublicKeyEd25519Hex = participantDoc.exists ? participantDoc.get('public_key_ed25519') : undefined;

    // Sin clave pública registrada (dispositivo Caso B que nunca completó
    // el registro opt-in, o `participants` mal formado) no hay forma de
    // verificar — se trata igual que un MAC inválido: señalado, nunca
    // borrado ni oculto (AC de #48).
    const verified = devicePublicKeyEd25519Hex
      ? verifyCaseBMac({
          meshState,
          devicePublicKeyEd25519Hex,
          backendPrivateKeyX25519Hex: backendEcdhPrivateKeyX25519Hex.value(),
        })
      : false;

    // `set(..., { merge: true })` en vez de `update()` — `update()` exige
    // que el documento ya exista en la vista de la función en ese instante
    // exacto, y el emulador local de Firestore mostró ser inconsistente en
    // ese punto justo después de `onDocumentCreated` (NOT_FOUND
    // intermitente); `set` con merge no tiene ese requisito y es el mismo
    // patrón que ya usan los demás escritores del proyecto
    // (`FirebaseMeshStateUploader`, `FirebaseIdentityConfirmationUploader`).
    await snapshot.ref.set({ mac_verificado: verified }, { merge: true });
  }
);

// Verificación de proximidad de "resuelto" (#55/#56/#59) — a diferencia de
// arriba, dispara con `onDocumentWritten` (no `onDocumentCreated`): la
// resolución llega como un `merge` sobre un documento que puede ya existir
// (subido antes por un gateway) o crearse en ese mismo momento (si nadie
// subió todavía esa secuencia de la víctima, #55). La guarda contra el loop
// de re-disparo no es del trigger sino de `shouldComputeProximity()`: solo
// procesa mientras `proximidad_verificada` no esté definida todavía — la
// propia escritura de vuelta de esta función dispara una invocación nueva
// que se ve a sí misma como "ya procesada" y no hace nada.
export const verifyResolutionProximity = onDocumentWritten('mesh_states/{docId}', async (event) => {
  const afterSnapshot = event.data?.after;
  if (!afterSnapshot?.exists) return;

  const meshState = afterSnapshot.data();
  if (!shouldComputeProximity(meshState)) return;

  // `undefined` no es un valor válido de Firestore — `null` es la forma de
  // "sin verificar" que sí se puede escribir (AC de #59: nunca oculta ni
  // descarta el documento, mismo principio que `mac_verificado`).
  const verified = computeProximityVerified(meshState) ?? null;

  await afterSnapshot.ref.set({ proximidad_verificada: verified }, { merge: true });
});
