# Formato del paquete de beacon

Formato binario que viaja dentro del campo `Manufacturer Specific Data` (AD type
`0xFF`) de un advertisement BLE legacy. Ver [Decisiones de arquitectura](#decisiones-de-arquitectura)
para el porqué de cada elección.

## `Versión` como discriminador de formato

Desde #38/#39, `Versión` deja de ser una constante fija y pasa a distinguir
**qué layout** trae el payload — no puede vivir en `Tipo de mensaje` porque en
el layout de Caso B ese campo ya no ocupa un byte propio (ver decisión 15):

| `Versión` | Layout                                    | Tamaño   |
| --------- | ------------------------------------------ | -------- |
| `0x01`    | Legado (sin cambios, incluye Caso A)       | 26 bytes |
| `0x02`    | Caso B — autenticado con MAC simétrico     | 27 bytes |

## Layout legado (`Versión = 0x01`, 26 bytes)

Todos los campos multi-byte son **little-endian**.

| Offset | Campo           | Tamaño | Tipo            | Descripción                                                              |
| ------ | --------------- | ------ | --------------- | ------------------------------------------------------------------------- |
| 0      | Magic           | 1      | `uint8`         | `0xE7` fijo — identifica el protocolo entre el ruido BLE ambiental        |
| 1      | Versión         | 1      | `uint8`         | `0x01`                                                                    |
| 2      | Tipo de mensaje | 1      | `uint8`         | `0`=BEACON, `1`=GATEWAY_ANNOUNCE, `2`=ACK_RECEIVED, `3`=FRAGMENTO_FIRMA (Caso A, ver abajo) |
| 3      | Device ID hash  | 6      | bytes crudos    | `SHA-256(UUID de instalación)` truncado a los primeros 6 bytes (pasa a derivarse de la clave pública Ed25519 una vez #40/#41 implementen la identidad — ver decisión 17; hasta entonces sigue siendo UUID) |
| 9      | Estado          | 1      | `uint8`         | `0`=SIN_CONFIRMAR, `1`=OK, `2`=AYUDA, `3`=SILENCIO_TIMEOUT, `4`=GATEWAY_DISPONIBLE |
| 10     | Latitud         | 4      | `int32` LE      | grados × 1e7                                                              |
| 14     | Longitud        | 4      | `int32` LE      | grados × 1e7                                                              |
| 18     | Timestamp       | 4      | `uint32` LE     | unix epoch, segundos, UTC                                                 |
| 22     | TTL / saltos    | 1      | `uint8`         | arranca en 16, se resta 1 por retransmisión, muere en 0                   |
| 23     | Nonce           | 2      | `uint16` LE     | aleatorio por beacon, para deduplicar junto al Device ID hash             |
| 25     | Secuencia       | 1      | `uint8`         | sube con cada cambio de estado; comparación simple, sin manejo de wraparound |

**Total: 26 bytes.**

`Tipo=3` (`FRAGMENTO_FIRMA`, Caso A) reserva el valor dentro de este mismo
layout — un dispositivo que nunca tuvo conectividad fragmenta su clave
pública Ed25519 + firma a través de varios beacons `FRAGMENTO_FIRMA`
consecutivos, verificables localmente por cualquier relay que junte
suficientes fragmentos, sin backend ni conexión (#38, Caso A). El framing
exacto a nivel de bytes (índice de fragmento, conteo total, cuántos bytes de
clave/firma por fragmento) **no está definido todavía** — queda para la
ticket de implementación de Caso A (#44/#45), fuera de alcance de #39.

### Envoltorio BLE (Manufacturer Specific Data, AD type 0xFF)

```
Length (1) + AD Type 0xFF (1) + Company ID 0xFFFF (2, prototipo) + payload (26) = 30 bytes
```

Cabe dentro del límite de 31 bytes de un advertisement BLE **legacy** (ver decisión sobre Extended Advertising abajo).

## Layout Caso B (`Versión = 0x02`, 27 bytes)

Beacon autenticado de un dispositivo que se registró con señal antes del
desastre (flujo opt-in de `docs/adr/0003-identidad-participantes-registro-opt-in.md`).
Usa el presupuesto completo de 27 bytes disponibles dentro del límite de 31
del advertisement legacy (31 − 4 de envoltorio fijo), en vez de los 26 que
usa el layout legado — ver decisión 15 para el desglose de dónde salen los 3
bytes extra.

Todos los campos multi-byte son **little-endian**.

| Offset | Campo        | Tamaño | Tipo         | Descripción                                                        |
| ------ | ------------ | ------ | ------------ | ------------------------------------------------------------------- |
| 0      | Magic        | 1      | `uint8`      | `0xE7` fijo, igual que el layout legado                             |
| 1      | Versión      | 1      | `uint8`      | `0x02`                                                               |
| 2      | TipoEstado   | 1      | `uint8`      | nibble alto = `Tipo de mensaje` (hoy solo `0`=BEACON), nibble bajo = `Estado` (mismos valores 0-4 del layout legado) |
| 3      | Device ID hash | 6    | bytes crudos | `SHA-256(clave pública Ed25519)` truncado a 6 bytes — Caso B requiere la identidad Ed25519 por construcción (la necesita para el ECDH), así que usa esta fórmula desde el día uno, aunque el layout legado siga en UUID hasta #40/#41 (ver decisión 17) |
| 9      | Latitud      | 4      | `int32` LE   | grados × 1e7                                                        |
| 13     | Longitud     | 4      | `int32` LE   | grados × 1e7                                                        |
| 17     | Timestamp    | 4      | `uint32` LE  | unix epoch, segundos, UTC                                           |
| 21     | TTL / saltos | 1      | `uint8`      | igual semántica que el layout legado                                |
| 22     | MAC          | 4      | bytes crudos | `HMAC-SHA256(K_shared, contenido)` truncado a 4 bytes — reemplaza a `Nonce` |
| 26     | Secuencia    | 1      | `uint8`      | igual semántica que el layout legado                                |

**Total: 27 bytes.**

### Identidad compartida (ECDH)

Al registrarse, el dispositivo deriva `K_shared` sin ningún handshake en
vivo, contra una **clave pública X25519 fija del backend, embebida en el
binario de ambas apps** (constante no-secreta, análoga a cómo ya se embebe
`Company ID`/Service UUID):

```
privkey_X25519_dispositivo = ConvertirEd25519aX25519(privkey_Ed25519_dispositivo)
K_shared = X25519(privkey_X25519_dispositivo, pubkey_X25519_backend)
```

La clave Ed25519 del dispositivo (identidad ya usada para firmar en Caso A,
ver #40/#41) se convierte a X25519 vía el mapa birracional estándar —
equivalente a `crypto_sign_ed25519_sk_to_curve25519`/`_pk_to_curve25519` de
libsodium — en vez de generar un segundo par de claves aparte. La clave del
backend nace directamente como X25519 (no tiene uso de firma, no necesita
conversión). Puede calcularse offline en cualquier momento, incluso antes de
tener señal; el dispositivo solo necesita subir su propia clave pública
Ed25519 a `participants` (Firestore) cuando recupere conectividad —
compatible sin cambios con el flujo asíncrono ya existente de ADR-0003. La
constante `backend_public_key_x25519_hex` y los vectores de prueba de ECDH
viven en `spec/test-vectors.json` (clave `ecdh`); el keypair se genera con
`backend/scripts/generate-beacon-auth-vectors.mjs` y su privada se resguarda
en `backend/secrets/` (gitignored, fuera de git). Es deliberadamente un
keypair **prototipo determinístico** (no entropía real) mientras no exista
ningún backend real que lo use — #48 (la Cloud Function de verificación)
debe generar un keypair nuevo con entropía real y custodia en Firebase
Secret Manager antes de ir a producción, reemplazando esta constante.

### Cómputo de MAC

```
contenido = Device ID hash (6) || TipoEstado (1) || Latitud (4) || Longitud (4) || Timestamp (4) || TTL (1) || Secuencia (1)   -- 21 bytes
MAC = HMAC-SHA256(K_shared, contenido)[:4]
```

`contenido` no incluye `Magic`, `Versión` ni el propio `MAC` — solo los
campos autenticados del beacon. El MAC ata la autenticación al contenido
exacto: un MAC válido capturado de un beacon legítimo no puede reusarse con
un `Estado` u otros campos distintos, porque el MAC cambia. La verificación
real ocurre en una Cloud Function nueva del backend (#48), nunca en el
teléfono `GATEWAY_ACTIVO` ni en el JS estático del Panel de rescate — ver
decisión 14 más abajo. Vectores de prueba (`K_shared` + contenido fijo →
`MAC` esperado) en `spec/test-vectors.json`, clave `mac_vectors`.

**MAC inválido**: nunca se descarta en silencio — se marca/señala para
revisión humana en el Panel de rescate (#49), mismo principio que la decisión
8 de no suprimir automáticamente una señal ambigua.

## Máquinas de estado (contexto de dominio)

Dos máquinas paralelas gobiernan cuándo se genera/actualiza un paquete. Solo la
Máquina A se implementa en Fase 1; la Máquina B (rol de red / gateway) es Fase 2.

### A. Estado de la persona (dispara el campo `Estado` y `Secuencia`)

```
DORMIDO
  --(sismo detectado / activación manual)--> ACTIVO_SIN_CONFIRMAR
ACTIVO_SIN_CONFIRMAR
  --(termina el sacudón, timer de gracia ~2 min)--> ESPERANDO_CONFIRMACION
ESPERANDO_CONFIRMACION
  --(usuario toca "Estoy bien")--> CONFIRMADO_OK
  --(usuario toca "Necesito ayuda")--> AYUDA_SOLICITADA
  --(timeout sin respuesta, 15-30 min)--> SILENCIO_TIMEOUT
CONFIRMADO_OK
  --(baja frecuencia de beacon, sigue retransmitiendo a otros)--> DORMIDO (bajo consumo)
SILENCIO_TIMEOUT
  --(usuario abre la app más tarde y confirma)--> CONFIRMADO_OK   ⚠ recuperación tardía, crítico
AYUDA_SOLICITADA
  --(usuario cancela / confirma que ya está bien)--> CONFIRMADO_OK
```

### B. Rol de red del teléfono — fuera de alcance en Fase 1

```
APAGADO --> SOLO_RETRANSMITE --> GATEWAY_ACTIVO --> SINCRONIZADO_IDLE
CUALQUIER_ESTADO --(batería < 15%)--> BAJO_CONSUMO --(batería > 25% o cargando)--> SOLO_RETRANSMITE
```

Bajo `BAJO_CONSUMO`, la prioridad de descarte al llenarse la caché es: OK primero,
AYUDA/SILENCIO_TIMEOUT nunca antes que OK. Esta regla depende de la Máquina B y
por tanto se implementa junto con ella en Fase 2 — la cola de retransmisión de
Fase 1 usa un LRU simple, sin esta priorización (ver decisión 9 abajo).

## Deduplicación

Clave: `DeviceIDHash + Nonce` para paquetes `Versión=0x01` (legado, sin
cambios). Para paquetes `Versión=0x02` (Caso B): `DeviceIDHash + MAC` — sigue
funcionando igual porque `MAC` incluye `Timestamp` en su entrada, así que
cambia en cada emisión nueva aunque el `Estado` no haya cambiado (ver
decisión 15). Caché con expiración de ~30 min, tope LRU de 500 entradas por
nodo. El emisor de un beacon se auto-registra en su propia caché de dedup al
emitir (ver decisión 12) — no hay una ruta de código separada para "es mi
propio beacon rebotado".

## Decisiones de arquitectura

Resueltas en sesión de grilling previa a la implementación. Registradas aquí
porque no son derivables del código ni de la tabla original.

1. **Stack**: nativo puro — Swift (iOS) + Kotlin (Android). Sin framework cross-platform, para tener control total sobre el framing BLE.
2. **Modelo BLE**: advertising/scanning puro, **sin conexión GATT** en Fase 1. La mención a "GATT crudo" del prompt original queda descartada — es 100% broadcast. ⚠ Revisado parcialmente por la decisión 13: el *lado que emite* en iOS sí usa GATT, por una restricción de la API que esta sesión de grilling no había detectado.
3. **Alcance de ejecución**: foreground-only para el MVP. iOS strippea `Manufacturer Specific Data` del advertisement en background — background real queda como riesgo conocido de Fase 2+.
4. **Extended Advertising**: descartado. `CoreBluetooth` no expone Extended Advertising para el rol de anunciante vía API pública en iOS, sin importar el chip. Fase 1 usa advertising **legacy** (máx. 31 bytes); no hay margen reservado para firma/autenticación futura con este formato.
5. **Endianness**: little-endian en todos los campos multi-byte (convención nativa de BLE/GAP).
6. **Device ID hash**: `SHA-256(UUID de instalación)` truncado a 6 bytes, calculado una vez y persistido (Keychain / EncryptedSharedPreferences) — estable entre sesiones. Vale para el layout legado hasta que #40/#41 implementen la identidad Ed25519 — ver decisión 17, que reemplaza el material de entrada sin tocar el mecanismo de truncamiento.
7. **Test de interoperabilidad**: `spec/test-vectors.json` es la fuente de verdad compartida. Los tests de Swift y Kotlin deben leer el mismo archivo y verificar encode + decode contra los mismos bytes — un round-trip aislado por plataforma no es suficiente, porque no detecta que ambas plataformas estén de acuerdo entre sí.
8. **Estructura del repo**: monorepo (`spec/`, `ios/`, `android/`).
9. **Retransmisión**: round-robin simple con ventana fija (~1s) por paquete en cola. Sin priorización por estado en Fase 1 — esa lógica pertenece a la Máquina B / `BAJO_CONSUMO` (Fase 2). Necesario porque `CBPeripheralManager` en iOS solo permite un advertisement activo a la vez.
10. **Secuencia**: comparación simple de enteros (`nueva > vieja`), sin aritmética de wraparound. Un dispositivo real cambia de estado un puñado de veces por evento; dar la vuelta a 256 cambios es un caso extremo documentado y no manejado.
11. **Log**: en pantalla dentro de la app, como mecanismo **principal** de verificación en campo (no solo consola/Xcode/Logcat) — necesario para poder separar físicamente los 3 teléfonos de prueba sin cables.
12. **Auto-dedup**: el emisor inserta su propio beacon en su caché de dedup al emitirlo, para que un rebote de su propio paquete se descarte por el mismo camino de "duplicado por caché", sin una ruta de código especial.
13. **Emisión en iOS: GATT en vez de Manufacturer Data (revisión de la decisión 2, ticket #6)**. `CBPeripheralManager.startAdvertising` en iOS solo admite `CBAdvertisementDataLocalNameKey` y `CBAdvertisementDataServiceUUIDsKey` en el rol periférico — Manufacturer Specific Data **no** es una clave soportada al anunciar (confirmado en la documentación de Apple y en una respuesta de un ingeniero de Apple DTS en su foro; cualquier otra clave produce error). Esto no se detectó en la sesión de grilling original, que se enfocó en el límite de 31 bytes y en Extended Advertising (decisión 4), no en qué claves admite la API de advertising del rol periférico. El rol *central* (escaneo) no tiene esta restricción — sí puede leer Manufacturer Data de otros peers (p. ej. Android) con normalidad.
    - **Android no cambia**: sigue emitiendo por advertising legacy puro con Manufacturer Data, sin GATT — no tiene esta limitación.
    - **iOS al emitir**: el advertisement solo señaliza "soy un nodo Farosos" (un Service UUID fijo + Local Name `"Farosos"`, ver `BeaconGattService` en `ios/Sources/BeaconRadio/`). El `BeaconPacket` real de 26 bytes viaja sin envoltorio adicional como el valor de una característica GATT de solo lectura; un central que reconoce el Service UUID se conecta, la lee, y se desconecta — el formato de wire de 26 bytes no cambia en absoluto, solo el transporte del lado emisor de iOS.
    - **iOS al escanear**: sigue escaneando ambos casos — decodifica Manufacturer Data directamente si el advertisement la trae (p. ej. de un peer Android), o se conecta por GATT si detecta el Service UUID de Farosos (p. ej. de otro peer iOS). Ambas rutas convergen en el mismo pipeline de dedup + log.
14. **Verificación del MAC de Caso B: solo en una Cloud Function del backend, nunca en el hardware de la malla** (#38/#39). Ni el teléfono `GATEWAY_ACTIVO` ni el JS estático del Panel de rescate verifican el MAC — cualquier secreto puesto en hosting estático sería extraíble por cualquier visitante. El teléfono gateway sigue siendo solo puente de conectividad, igual que ya opera con #34/#35.
15. **Caso B: MAC simétrico post-registro (ECDH + HMAC), no cadena de hashes/TESLA**. Se evaluó un esquema estilo S/KEY + TESLA (Perrig et al.) y se descartó porque su disclosure delay depende de una cota de latencia de red indeterminable en una malla BLE de desastre real (relays intermitentes, TTL de hasta 16 saltos) — ver `docs/research/beacon-auth-prior-art.md`. El MAC (`HMAC-SHA256(K_shared, contenido)[:4]`) ata la autenticación al contenido exacto, sin retraso de revelación ni sincronización de tiempo. Los 4 bytes del `MAC` en el layout de Caso B salen de: 2 bytes que ocupaba `Nonce` (absorbido íntegro en el MAC), 1 byte de margen del envoltorio que el layout legado nunca usó (31−30), y 1 byte liberado al empaquetar `Tipo`+`Estado` en un solo byte (ambos usaban ≤5 de 256 valores posibles). Se evaluó recortar `Device ID hash` para ganar más espacio y se descartó — a escala nacional (~10M dispositivos) da ~16% de probabilidad de colisión con 48 bits, ya al límite razonable.
16. **`TipoEstado` empaquetado en nibbles (Caso B)**: nibble alto = `Tipo de mensaje`, nibble bajo = `Estado`. Ambos enums usan pocos valores (≤5 de 256), así que comparten un solo byte en vez de dos — parte del presupuesto de bytes de la decisión 15. Hoy el nibble de `Tipo` solo toma el valor `0` (`BEACON`) en la práctica: `GATEWAY_ANNOUNCE`/`ACK_RECEIVED` quedan exclusivamente en el layout legado (#38), sin cambios.
17. **`device_id_hash` deriva de la clave pública Ed25519, no del UUID de instalación** (#38). Mismo mecanismo (`SHA-256(...)`) y mismo largo (6 bytes) — solo cambia el material de entrada, ahora estable y verificable criptográficamente en vez de un UUID arbitrario. Caso B (`Versión=0x02`) usa esta fórmula desde el día uno, porque requiere la identidad Ed25519 por construcción (#42/#43 dependen de #40/#41). El layout legado (`Versión=0x01`, Caso A incluido) sigue en UUID (decisión 6) hasta que #40/#41 implementen la identidad — a partir de ahí ambos layouts quedan en la misma fórmula. Migración de dispositivos ya provisionados con `device_id_hash` basado en UUID: sin resolver, señalado explícitamente como hueco abierto en el spec de #38 ("Further Notes").

## Criterio de éxito de Fase 1

Con 3 teléfonos físicos (idealmente 1 iPhone + 2 Android), colocar el
dispositivo B fuera de rango directo de A pero dentro de rango de C, de modo
que un beacon de A solo pueda llegar a C retransmitido por B. Confirmar en el
log de C que el beacon de A llegó con `TTL` reducido en 1 respecto al que
emitió A, dentro de paredes reales de un edificio.
