# Formato del paquete de beacon (26 bytes)

Formato binario que viaja dentro del campo `Manufacturer Specific Data` (AD type
`0xFF`) de un advertisement BLE legacy. Ver [Decisiones de arquitectura](#decisiones-de-arquitectura)
para el porqué de cada elección.

## Layout

Todos los campos multi-byte son **little-endian**.

| Offset | Campo           | Tamaño | Tipo            | Descripción                                                              |
| ------ | --------------- | ------ | --------------- | ------------------------------------------------------------------------- |
| 0      | Magic           | 1      | `uint8`         | `0xE7` fijo — identifica el protocolo entre el ruido BLE ambiental        |
| 1      | Versión         | 1      | `uint8`         | `0x01`                                                                    |
| 2      | Tipo de mensaje | 1      | `uint8`         | `0`=BEACON, `1`=GATEWAY_ANNOUNCE, `2`=ACK_RECEIVED                        |
| 3      | Device ID hash  | 6      | bytes crudos    | `SHA-256(UUID de instalación)` truncado a los primeros 6 bytes            |
| 9      | Estado          | 1      | `uint8`         | `0`=SIN_CONFIRMAR, `1`=OK, `2`=AYUDA, `3`=SILENCIO_TIMEOUT, `4`=GATEWAY_DISPONIBLE |
| 10     | Latitud         | 4      | `int32` LE      | grados × 1e7                                                              |
| 14     | Longitud        | 4      | `int32` LE      | grados × 1e7                                                              |
| 18     | Timestamp       | 4      | `uint32` LE     | unix epoch, segundos, UTC                                                 |
| 22     | TTL / saltos    | 1      | `uint8`         | arranca en 16, se resta 1 por retransmisión, muere en 0                   |
| 23     | Nonce           | 2      | `uint16` LE     | aleatorio por beacon, para deduplicar junto al Device ID hash             |
| 25     | Secuencia       | 1      | `uint8`         | sube con cada cambio de estado; comparación simple, sin manejo de wraparound |

**Total: 26 bytes.**

### Envoltorio BLE (Manufacturer Specific Data, AD type 0xFF)

```
Length (1) + AD Type 0xFF (1) + Company ID 0xFFFF (2, prototipo) + payload (26) = 30 bytes
```

Cabe dentro del límite de 31 bytes de un advertisement BLE **legacy** (ver decisión sobre Extended Advertising abajo).

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

Clave: `DeviceIDHash + Nonce`. Caché con expiración de ~30 min, tope LRU de 500
entradas por nodo. El emisor de un beacon se auto-registra en su propia caché de
dedup al emitir (ver decisión 12) — no hay una ruta de código separada para
"es mi propio beacon rebotado".

## Decisiones de arquitectura

Resueltas en sesión de grilling previa a la implementación. Registradas aquí
porque no son derivables del código ni de la tabla original.

1. **Stack**: nativo puro — Swift (iOS) + Kotlin (Android). Sin framework cross-platform, para tener control total sobre el framing BLE.
2. **Modelo BLE**: advertising/scanning puro, **sin conexión GATT** en Fase 1. La mención a "GATT crudo" del prompt original queda descartada — es 100% broadcast. ⚠ Revisado parcialmente por la decisión 13: el *lado que emite* en iOS sí usa GATT, por una restricción de la API que esta sesión de grilling no había detectado.
3. **Alcance de ejecución**: foreground-only para el MVP. iOS strippea `Manufacturer Specific Data` del advertisement en background — background real queda como riesgo conocido de Fase 2+.
4. **Extended Advertising**: descartado. `CoreBluetooth` no expone Extended Advertising para el rol de anunciante vía API pública en iOS, sin importar el chip. Fase 1 usa advertising **legacy** (máx. 31 bytes); no hay margen reservado para firma/autenticación futura con este formato.
5. **Endianness**: little-endian en todos los campos multi-byte (convención nativa de BLE/GAP).
6. **Device ID hash**: `SHA-256(UUID de instalación)` truncado a 6 bytes, calculado una vez y persistido (Keychain / EncryptedSharedPreferences) — estable entre sesiones.
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

## Criterio de éxito de Fase 1

Con 3 teléfonos físicos (idealmente 1 iPhone + 2 Android), colocar el
dispositivo B fuera de rango directo de A pero dentro de rango de C, de modo
que un beacon de A solo pueda llegar a C retransmitido por B. Confirmar en el
log de C que el beacon de A llegó con `TTL` reducido en 1 respecto al que
emitió A, dentro de paredes reales de un edificio.
