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
import { latestPerDevice, historyForDevice, attachParticipantName, sortByMostRecent } from './meshView.mjs';

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

function renderCurrentState() {
  const latest = sortByMostRecent(
    latestPerDevice(meshStates).map((state) => attachParticipantName(state, participantsByHash))
  );

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

    row.appendChild(statusCell(state.status));
    row.appendChild(locationCell(state.latitude, state.longitude));
    row.appendChild(timeCell(state.uploaded_at));

    const gatewaysCell = document.createElement('td');
    gatewaysCell.textContent = String((state.confirmed_by_gateways ?? []).length);
    row.appendChild(gatewaysCell);

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
  const history = historyForDevice(meshStates, deviceIdHash);
  historyBody.textContent = '';
  for (const state of history) {
    const row = document.createElement('tr');

    const sequenceCell = document.createElement('td');
    sequenceCell.textContent = String(state.sequence);
    row.appendChild(sequenceCell);

    row.appendChild(statusCell(state.status));
    row.appendChild(locationCell(state.latitude, state.longitude));
    row.appendChild(timeCell(state.uploaded_at));

    historyBody.appendChild(row);
  }
}

function statusCell(status) {
  const cell = document.createElement('td');
  cell.textContent = STATUS_LABELS[status] ?? status;
  cell.className = 'status status-' + status.toLowerCase();
  return cell;
}

function locationCell(latitude, longitude) {
  const cell = document.createElement('td');
  cell.textContent = `${latitude}, ${longitude}`;
  return cell;
}

function timeCell(uploadedAtEpochSeconds) {
  const cell = document.createElement('td');
  cell.textContent = new Date(uploadedAtEpochSeconds * 1000).toLocaleString('es');
  return cell;
}
