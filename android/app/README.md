# App de Android (ticket #30)

## Lo que ya está hecho (automatizado, verificado con `gradle test` + `gradle build`)

- Pantalla de registro opt-in (`RegistrationScreen`/`RegistrationViewModel`) mostrada
  solo en la primera apertura — pide nombre (obligatorio) y contacto (opcional);
  "Continuar" nunca requiere conectividad.
- Persistencia local en `EncryptedSharedPreferences` (`ParticipantStore`, mismo
  patrón que `DeviceIdentity`) — no se vuelve a pedir en aperturas siguientes.
- Subida a `participants/{device_id_hash}` desacoplada de `GATEWAY_ACTIVO`
  (`ParticipantUploadCoordinator`, módulo Gradle testeado `:participantregistration`):
  se dispara con cualquier señal de `ConnectivityMonitor`, con reintento
  automático si falla o si no había conectividad al momento del registro.
- Firebase Auth (sesión anónima) + Firestore ya integrados vía Gradle
  (`FirebaseParticipantUploader`) — dependencia agregada en
  `android/app/build.gradle.kts` (BoM `firebase-bom:34.17.0`,
  `firebase-auth`/`firebase-firestore`) y permiso `INTERNET` agregado al
  manifest (primer código de esta app que hace requests HTTP reales).
- `gradle build` compila la app completa; `gradle test` corre los 7 tests
  nuevos de `:participantregistration` (y el resto de módulos) sin tocar
  Firebase para nada — son puros, sin red.

## Lo que falta — requiere tu cuenta real de Firebase, no lo puedo hacer yo

### 1. Registrar la app de Android en el proyecto Firebase existente

En la [consola de Firebase](https://console.firebase.google.com/) del proyecto
`farosos-project` (el mismo de `backend/` y de la app de iOS, ya existe):

- **Project settings → Your apps → Add app → Android**.
- Package name: `com.farosos.app`.
- SHA-1 opcional (Anonymous Auth no lo requiere; solo hace falta si más
  adelante se agrega Google Sign-In).
- Descargá el `google-services.json` generado.

### 2. Agregar el archivo al proyecto

Colocá el archivo descargado en:

```
android/app/google-services.json
```

**Importante — a diferencia de iOS:** el plugin de Google Services
(`com.google.gms.google-services`) se aplica solo si este archivo existe
(ver el `if (file("google-services.json").exists())` en
`android/app/build.gradle.kts`) — así el build multi-módulo no se rompe
mientras el archivo no exista (Gradle configura todos los subproyectos
aunque apuntes a uno solo, y el plugin falla la configuración entera sin el
archivo). Una vez agregado, no hace falta tocar nada más: el plugin genera
la config de Firebase automáticamente y `FirebaseApp` se auto-inicializa al
arrancar la app.

### 3. Verificar en el emulador/dispositivo

- `gradle :app:installDebug` (o build+run desde Android Studio). Debería
  aparecer la pantalla de registro en el primer arranque.
- Completar el registro con el emulador **sin red** (`adb shell svc wifi
  disable` + `adb shell svc data disable`) — "Continuar" debe funcionar
  igual, sin crashear.
- Reactivar la red (`adb shell svc wifi enable` + `adb shell svc data
  enable`) y confirmar en la consola de Firestore (`farosos-project` →
  `participants`) que aparece un documento con el `device_id_hash` del
  emulador.

## Notas

- Sin `google-services.json`, cualquier llamada real a Firebase Auth/Firestore
  lanza `IllegalStateException: Default FirebaseApp is not initialized` —
  pero recién en el primer intento de subida (cuando `ConnectivityMonitor`
  detecta conectividad), no al abrir la app. Más tolerante que iOS (que
  crashea en cada arranque por `FirebaseApp.configure()`), pero sigue siendo
  un estado incompleto hasta completar el paso 1-2.
- `google-services.json` es seguro de commitear al repo, igual que
  `GoogleService-Info.plist` en iOS — es config de cliente, no un secreto
  como la service account key de `backend/`.
- El ticket #32 (Gateway sube datos reales al backend — Android) reutiliza
  `FirebaseParticipantUploader`/Firestore ya wireados acá para subir
  `mesh_states`.
