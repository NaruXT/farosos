# Backend de agregación (ticket #28)

Config de Firebase (Firestore + Auth + Hosting) para el piloto de Fase 3. Ver
`docs/adr/0001-backend-agregacion-firebase-supabase.md`,
`docs/adr/0002-dedup-multi-gateway-id-deterministico.md` y
`docs/adr/0003-identidad-participantes-registro-opt-in.md` en la raíz del repo
para el porqué de cada decisión.

## Lo que ya está hecho (automatizado, verificado con el emulador local)

- `firestore.rules`: cualquier sesión autenticada (anónima o password) puede
  escribir en `mesh_states`/`participants`; solo la credencial compartida
  (password) puede leer. Todo lo demás, denegado.
- `firestore.indexes.json`: vacío por ahora — #33 (panel de rescate) agrega
  índices según los queries reales que necesite.
- `tests/firestore-rules.test.mjs`: suite completa contra el emulador local,
  incluyendo el caso de deduplicación (`docs/adr/0002-...`). `npm test` la
  corre sin necesidad de un proyecto real ni de conectividad — todo local.
- `scripts/verify-dedup.mjs`: mismo chequeo de deduplicación, pero contra el
  proyecto real vía Admin SDK — correlo una vez que el proyecto exista y las
  reglas estén desplegadas (ver abajo).

## Lo que falta — requiere tu cuenta real de Firebase, no lo puedo hacer yo

### 1. Instalar Java 11+ (si no lo tenés)

El emulador de Firestore necesita Java 11 o superior. Este host tiene Java 8
en el `PATH` por defecto, pero ya hay un OpenJDK 26 instalado vía Homebrew sin
enlazar. Para correr `npm test` acá:

```
JAVA_HOME="/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home" \
PATH="/opt/homebrew/opt/openjdk/libexec/openjdk.jdk/Contents/Home/bin:$PATH" \
npm test
```

O corré `brew link openjdk --force` si querés que quede como el Java por
defecto de esta máquina (fuera del alcance de este ticket, tu decisión).

### 2. Login y creación del proyecto

```
npx firebase login
npx firebase projects:create <un-project-id-de-tu-elección>
```

Copiá `.firebaserc.example` a `.firebaserc` y reemplazá el project ID:

```
cp .firebaserc.example .firebaserc
```

### 3. Habilitar Firestore, Authentication y Hosting

En la [consola de Firebase](https://console.firebase.google.com/) del
proyecto recién creado (esto no es scriptable de forma confiable vía CLI):

- **Firestore Database** → crear base de datos, modo producción, la región
  más cercana al piloto.
- **Authentication** → habilitar los proveedores **Anonymous** (para las apps
  de los teléfonos) y **Email/Password** (para la credencial compartida del
  panel de rescate).
- **Hosting** → habilitar (el contenido real lo agrega #33).

### 4. Crear la credencial compartida del panel de rescate

En **Authentication → Users → Add user**, con un email y password que vos
elijas — no lo genero yo, es una credencial real que vas a compartir con
quien tenga acceso al panel. Guardala de forma segura (no la subas al repo).

### 5. Desplegar las reglas

```
npx firebase deploy --only firestore:rules,firestore:indexes,hosting
```

### 6. Verificar el criterio de deduplicación contra el proyecto real

Generá una clave de cuenta de servicio (**Project Settings → Service
accounts → Generate new private key**) y corré:

```
GOOGLE_APPLICATION_CREDENTIALS=/ruta/a/tu-clave.json npm run verify-dedup
```

Esto escribe dos veces el mismo `{device_id_hash}_{sequence}` de prueba,
confirma que existe un único documento, y lo borra al terminar.

## Notas

- `node_modules/`, `.firebaserc` y cualquier clave de cuenta de servicio están
  en `.gitignore` de este directorio — no deberían terminar en el repo.
- Los siguientes tickets (#29-#32, #33) asumen que este setup ya existe.
