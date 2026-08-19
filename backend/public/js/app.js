// Glue de Firebase + DOM del Panel de rescate (#33) — sin tests, mismo molde
// que los adapters de app de las apps móviles (`FirebaseParticipantUploader`,
// etc.): la lógica real y testeable vive en `meshView.mjs`, esto solo
// conecta Auth/Firestore con la pantalla.
import { initializeApp } from 'https://www.gstatic.com/firebasejs/11.10.0/firebase-app.js';
import {
  getAuth,
  onAuthStateChanged,
  signInWithEmailAndPassword,
  signOut,
} from 'https://www.gstatic.com/firebasejs/11.10.0/firebase-auth.js';
import { getFirestore, collection, onSnapshot } from 'https://www.gstatic.com/firebasejs/11.10.0/firebase-firestore.js';
import { firebaseConfig } from './firebase-config.js';
import {
  latestPerDevice,
  historyForDevice,
  attachParticipantInfo,
  isCasoA,
  verificationLabel,
  statusLabel,
  sortByMostRecent,
  resolutionInfo,
  attendingList,
  hasBeaconData,
} from './meshView.mjs';

const PROXIMITY_LABELS = {
  verificada: 'Proximidad verificada',
  fuera_de_rango: 'Fuera de 100m',
  sin_verificar: 'Proximidad sin verificar',
};

const STATUS_LABELS = {
  SIN_CONFIRMAR: 'Sin confirmar',
  OK: 'Bien',
  AYUDA: 'Necesita ayuda',
  SILENCIO_TIMEOUT: 'Sin respuesta',
  GATEWAY_DISPONIBLE: 'Gateway disponible',
};

const app = initializeApp(firebaseConfig);
const auth = getAuth(app);
const db = getFirestore(app);

const loginSection = document.getElementById('login-section');
const appSection = document.getElementById('app-section');
const logoutButton = document.getElementById('logout-button');
const loginForm = document.getElementById('login-form');
const loginError = document.getElementById('login-error');
const currentStateSection = document.getElementById('current-state-section');
const currentStateBody = document.getElementById('current-state-body');
const currentStateEmpty = document.getElementById('current-state-empty');
const historySection = document.getElementById('history-section');
const historyTitle = document.getElementById('history-title');
const historyBody = document.getElementById('history-body');
const historyBackButton = document.getElementById('history-back-button');

let meshStates = [];
let participantsByHash = {};
let unsubscribeMeshStates = null;
let unsubscribeParticipants = null;
let currentHistoryDeviceHash = null;

loginForm.addEventListener('submit', (event) => {
  event.preventDefault();
  loginError.hidden = true;
  const email = document.getElementById('login-email').value;
  const password = document.getElementById('login-password').value;
  signInWithEmailAndPassword(auth, email, password).catch((error) => {
    loginError.textContent = 'No se pudo iniciar sesión: ' + error.message;
    loginError.hidden = false;
  });
});

logoutButton.addEventListener('click', () => signOut(auth));
historyBackButton.addEventListener('click', showCurrentState);

// `onAuthStateChanged` es la única puerta hacia los listeners de Firestore —
// sin sesión, nunca se llama `startListeners`, así que no hay ningún intento
// de leer `mesh_states`/`participants` (las reglas ya lo negarían, pero la
// UI tampoco debería intentarlo).
onAuthStateChanged(auth, (user) => {
  if (user) {
    loginSection.hidden = true;
    appSection.hidden = false;
    logoutButton.hidden = false;
    startListeners();
  } else {
    loginSection.hidden = false;
    appSection.hidden = true;
    logoutButton.hidden = true;
    stopListeners();
    meshStates = [];
    participantsByHash = {};
    showCurrentState();
  }
});

function startListeners() {
  unsubscribeMeshStates = onSnapshot(collection(db, 'mesh_states'), (snapshot) => {
    meshStates = snapshot.docs.map((doc) => doc.data());
    render();
  });
  unsubscribeParticipants = onSnapshot(collection(db, 'participants'), (snapshot) => {
    participantsByHash = {};
    snapshot.docs.forEach((doc) => {
      participantsByHash[doc.id] = doc.data();
    });
    render();
  });
}

function stopListeners() {
  unsubscribeMeshStates?.();
  unsubscribeParticipants?.();
  unsubscribeMeshStates = null;
  unsubscribeParticipants = null;
}

function render() {
  renderCurrentState();
  if (currentHistoryDeviceHash) {
    renderHistory(currentHistoryDeviceHash);
  }
}

/** Enriquece cada estado con `attachParticipantInfo` contra el mapa de
 * participants actual — mismo paso previo al render en ambas tablas
 * (estado actual e historial). */
function withParticipantInfo(states) {
  return states.map((state) => attachParticipantInfo(state, participantsByHash));
}

function renderCurrentState() {
  const latest = sortByMostRecent(withParticipantInfo(latestPerDevice(meshStates)));

  currentStateBody.textContent = '';
  currentStateEmpty.hidden = latest.length > 0;

  for (const state of latest) {
    const row = document.createElement('tr');

    const personCell = document.createElement('td');
    const personButton = document.createElement('button');
    personButton.type = 'button';
    personButton.className = 'link-button';
    personButton.textContent = state.name ?? state.device_id_hash;
    personButton.addEventListener('click', () => showHistory(state.device_id_hash, state.name));
    personCell.appendChild(personButton);
    row.appendChild(personCell);

    const contactCell = document.createElement('td');
    contactCell.textContent = state.contact ?? '—';
    row.appendChild(contactCell);

    row.appendChild(statusCell(state));
    row.appendChild(locationCell(state.latitude, state.longitude));
    row.appendChild(timeCell(state.uploaded_at));

    const gatewaysCell = document.createElement('td');
    gatewaysCell.textContent = String((state.confirmed_by_gateways ?? []).length);
    row.appendChild(gatewaysCell);

    row.appendChild(verificationCell(state));
    row.appendChild(rescueCell(state));

    currentStateBody.appendChild(row);
  }
}

function showHistory(deviceIdHash, name) {
  currentHistoryDeviceHash = deviceIdHash;
  currentStateSection.hidden = true;
  historySection.hidden = false;
  historyTitle.textContent = 'Historial — ' + (name ?? deviceIdHash);
  renderHistory(deviceIdHash);
}

function showCurrentState() {
  currentHistoryDeviceHash = null;
  historySection.hidden = true;
  currentStateSection.hidden = false;
}

function renderHistory(deviceIdHash) {
  const history = withParticipantInfo(historyForDevice(meshStates, deviceIdHash));
  historyBody.textContent = '';
  for (const state of history) {
    const row = document.createElement('tr');

    const sequenceCell = document.createElement('td');
    sequenceCell.textContent = String(state.sequence);
    row.appendChild(sequenceCell);

    row.appendChild(statusCell(state));
    row.appendChild(locationCell(state.latitude, state.longitude));
    row.appendChild(timeCell(state.uploaded_at));
    row.appendChild(verificationCell(state));
    row.appendChild(rescueCell(state));

    historyBody.appendChild(row);
  }
}

/** DOM de la columna "Verificación" (#54/#49) a partir de `verificationLabel`
 * (`meshView.mjs`, testeada) — acá solo se construyen los elementos, la
 * decisión de qué mostrar vive en la función pura. Caso A mantiene
 * exactamente el texto de antes ("No verificado", #49 AC3); Caso B señalado
 * usa un texto propio ("MAC inválido") porque la razón es distinta —
 * `isCasoA` solo decide el texto, no si se muestra algo. */
function verificationCell(state) {
  const cell = document.createElement('td');
  cell.className = 'verification';
  const label = verificationLabel(state);

  if (label.verified) {
    const verified = document.createElement('span');
    verified.className = 'verification-verified';
    verified.textContent = 'Verificado';
    cell.appendChild(verified);
    return cell;
  }

  const unverified = document.createElement('span');
  unverified.className = 'verification-unverified';
  unverified.textContent = isCasoA(state) ? 'No verificado' : 'MAC inválido';
  cell.appendChild(unverified);

  if (label.identityConfirmed) {
    const confirmed = document.createElement('span');
    confirmed.className = 'verification-confirmed';
    confirmed.textContent = 'Identidad confirmada';
    cell.appendChild(confirmed);
  }

  return cell;
}

/** `statusLabel` (`meshView.mjs`, testeada) normaliza el `status` crudo de
 * Caso A (ya string) y Caso B (entero del wire, #48) a la misma etiqueta
 * antes de que esta función toque el DOM. Un documento parcial (creado solo
 * por una resolución que llegó antes que el beacon real, #55/#60) no tiene
 * `status` — `hasBeaconData` lo detecta antes de que `label.toLowerCase()`
 * reviente sobre `undefined`. */
function statusCell(state) {
  const cell = document.createElement('td');
  if (!hasBeaconData(state)) {
    cell.textContent = 'Sin datos de beacon';
    cell.className = 'status status-sin-datos';
    return cell;
  }
  const label = statusLabel(state);
  cell.textContent = STATUS_LABELS[label] ?? label;
  cell.className = 'status status-' + label.toLowerCase();
  return cell;
}

function locationCell(latitude, longitude) {
  const cell = document.createElement('td');
  cell.textContent = latitude != null && longitude != null ? `${latitude}, ${longitude}` : '—';
  return cell;
}

function timeCell(uploadedAtEpochSeconds) {
  const cell = document.createElement('td');
  cell.textContent = uploadedAtEpochSeconds != null ? new Date(uploadedAtEpochSeconds * 1000).toLocaleString('es') : '—';
  return cell;
}

function appendSpan(cell, className, text) {
  const span = document.createElement('span');
  span.className = className;
  span.textContent = text;
  cell.appendChild(span);
}

/** Columna "Rescate" (#55/#60): quién marcó "resuelto" y cuándo, el estado
 * de proximidad (`resolutionInfo`, `meshView.mjs`, testeada — nunca oculta
 * el caso por ninguno de los tres valores posibles), y la lista completa de
 * quienes marcaron "atendiendo" (`attendingList`). Un caso sin ninguna de
 * las dos señales muestra "—", igual que el resto de columnas opcionales
 * del panel (ej. `contactCell`). */
function rescueCell(state) {
  const cell = document.createElement('td');
  cell.className = 'rescue';

  const resolution = resolutionInfo(state);
  const attending = attendingList(state);

  if (!resolution.resolved && attending.length === 0) {
    cell.textContent = '—';
    return cell;
  }

  if (resolution.resolved) {
    const when = resolution.resolvedAt != null ? new Date(resolution.resolvedAt * 1000).toLocaleString('es') : '—';
    appendSpan(cell, 'rescue-resolved', `Resuelto por ${resolution.resolvedBy ?? '—'} (${when})`);
    appendSpan(cell, 'rescue-proximity rescue-proximity-' + resolution.proximity, PROXIMITY_LABELS[resolution.proximity]);
  }

  if (attending.length > 0) {
    appendSpan(cell, 'rescue-attending', 'Atendiendo: ' + attending.map((entry) => entry.device_id_hash).join(', '));
  }

  return cell;
}
