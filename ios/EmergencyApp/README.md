# App de iOS (ticket #29)

## Lo que ya está hecho (automatizado, verificado con `swift test` + `xcodebuild`)

- Pantalla de registro opt-in (`RegistrationView`/`RegistrationViewModel`) mostrada
  solo en la primera apertura — pide nombre (obligatorio) y contacto (opcional);
  "Continuar" nunca requiere conectividad.
- Persistencia local en Keychain (`KeychainParticipantStore`, mismo patrón que
  `KeychainDeviceIdentity`) — no se vuelve a pedir en aperturas siguientes.
- Subida a `participants/{device_id_hash}` desacoplada de `GATEWAY_ACTIVO`
  (`ParticipantUploadCoordinator`, paquete SPM testeado `ParticipantRegistration`):
  se dispara con cualquier señal de `ConnectivityMonitor`, con reintento
  automático si falla o si no había conectividad al momento del registro.
- Firebase Auth (sesión anónima) + Firestore ya integrados vía SPM
  (`FirebaseParticipantUploader`) — dependencia agregada en `project.yml`
  (`firebase-ios-sdk`, productos `FirebaseCore`/`FirebaseAuth`/`FirebaseFirestore`).
- `xcodebuild -scheme EmergencyApp build` compila y linkea correctamente contra
  el proyecto real `farosos-project` (backend de #28).

## Lo que falta — requiere tu cuenta real de Firebase, no lo puedo hacer yo

### 1. Registrar la app de iOS en el proyecto Firebase existente

En la [consola de Firebase](https://console.firebase.google.com/) del proyecto
`farosos-project` (el mismo de `backend/`, ya existe):

- **Project settings → Your apps → Add app → iOS**.
- Bundle ID: `com.farosos.EmergencyApp`.
- Descargá el `GoogleService-Info.plist` generado.

### 2. Agregar el archivo al proyecto

Colocá el archivo descargado en:

```
ios/EmergencyApp/Sources/GoogleService-Info.plist
```

XcodeGen lo detecta automáticamente como recurso porque vive dentro de la
carpeta `Sources` declarada en `project.yml` — no hace falta editar el YAML.
Después corré:

```
cd ios/EmergencyApp && xcodegen generate
```

Sin este archivo, `FirebaseApp.configure()` (llamado en `FarososApp.init()`)
crashea al arrancar con `com.firebase.core` — confirmado en esta sesión,
comportamiento esperado hasta que el archivo real exista.

### 3. Verificar en el simulador/dispositivo

- Build + run. Debería aparecer la pantalla de registro en el primer arranque.
- Completar el registro con el simulador **sin red** (Features → Toggle
  Network Link Conditioner, o apagar Wi-Fi del Mac) — "Continuar" debe
  funcionar igual.
- Reactivar la red y confirmar en la consola de Firestore
  (`farosos-project` → `participants`) que aparece un documento con el
  `device_id_hash` del simulador.

## Notas

- `GoogleService-Info.plist` es seguro de commitear (es config de cliente,
  no una credencial secreta como la service account key de `backend/`) —
  decisión tuya si preferís no hacerlo de todos modos.
- El ticket #31 (Gateway sube datos reales al backend — iOS) reutiliza
  `FirebaseParticipantUploader`/Firestore ya wireados acá para subir
  `mesh_states`.
