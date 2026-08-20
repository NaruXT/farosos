# Investigación: iPhone atascado en `CBPeripheral.state == .connecting` contra host GATT de Android

Investigación de apoyo para el issue #64 (verificación de campo cross-platform del chat directo, issue #61/#63/#62).
Reproducido de forma consistente: central iOS (`CBCentralManager`, iPhone 15, iOS 26.0) conecta contra un `BluetoothGattServer` de Android anunciando via `BluetoothLeAdvertiser`.
El `CBPeripheral` obtenido correctamente via `retrievePeripherals(withIdentifiers:)` queda en `state == 1` (`.connecting`) indefinidamente, sin disparar jamás `didConnect`, `didFailToConnect` ni `didDisconnectPeripheral`.
Del lado Android, `BluetoothGattServerCallback.onConnectionStateChange` tampoco dispara nunca, y un `adb bugreport` con `btsnoop_hci.log` parseado via `tshark` muestra cero actividad HCI de cualquier tipo durante la ventana de bloqueo.
iOS-host+Android-central y Android-host+Android-central funcionan bien; solo Android-host+iOS-central está roto.
Ver el issue #64 para el detalle completo de lo ya descartado (chipset, `CBPeripheral` stale, race del `BleScanner` de malla, bug de ACK de CCCD, toggle/reinicio de Bluetooth, bug conocido de iOS 17.x en el hilo 742497).

La lead priorizada de esta investigación: la captura HCI del lado Android muestra que el `BluetoothLeAdvertiser` de Android emite comandos de la familia `LE Set Extended Advertising *` (no comandos legacy clásicos) al iniciar el advertisement del chat, configurado via la API vieja `AdvertiseSettings`/`AdvertiseData` (no `AdvertisingSetParameters`).

---

## Resuelto (2026-08-20, misma sesión)

**Causa raíz real, confirmada con código propio y un trace de PacketLogger real: no es un bug interno de Apple, es un bug de Farosos.**
`chatPeerDirectory` en iOS (`ChatPeerDirectory.swift`) se poblaba desde `onManufacturerData`, el mismo callback que procesa el beacon general de la malla.
Android anuncia el beacon general (`BleAdvertiser.kt`) y el servicio GATT del chat (`ChatGattServer.kt`) como dos sesiones de advertising independientes, cada una con su propia dirección BLE aleatoria asignada por el sistema.
El `CBPeripheral` que terminaba cacheado para "abrir chat" podía ser el del beacon general, no el que realmente aloja el `BluetoothGattServer` del chat - `connect()` apuntaba entonces a una dirección que Android nunca escuchaba, sin ningún error visible.

Un trace de PacketLogger real (decodificando a mano la dirección objetivo de `LE Extended Create Connection` contra la dirección real del dispositivo Android) confirmó el mecanismo exacto: la dirección a la que apuntaba iOS nunca coincidía con la dirección real del host Android.
Encima, Android no expone ninguna API pública para fijar una dirección BLE estable en su advertising (`AdvertisingSetParameters.Builder` no tiene `setOwnAddressType` en la documentación oficial) - el sistema le asigna una dirección aleatoria resoluble (RPA) que puede rotar entre que el teléfono descubre el caso y que el usuario toca "Abrir chat", y sin bonding (decisión explícita de #61) CoreBluetooth no puede resolver una RPA rotada de vuelta a la misma identidad.
El patrón de auto-cancelación de CoreBluetooth documentado en la sección 2 (`LE Extended Create Connection` seguido de `LE Create Connection Cancel` cada pocos segundos, sin escalar nunca un error a la app) sí se confirmó en el trace real de Farosos - pero como síntoma de este problema de direccionamiento, no como el bug interno `FB21100266` de otro desarrollador.

**Fix aplicado, en dos partes:**

1. `ios/EmergencyApp/Sources/BleScanner.swift`: distingue el anuncio del chat del beacon general revisando el Service UUID del chat antes que el manufacturer data, enrutándolo a un callback nuevo (`onChatHostDiscovered`) que alimenta `chatPeerDirectory` por separado - mismo chequeo que Android ya hacía en su propio `BleScanner.onScanResult`.
2. `ios/EmergencyApp/Sources/ChatCentralConnection.swift`: mientras una conexión está pendiente, escanea activamente filtrado al Service UUID del chat y al `device_id_hash` del peer, y un timer cada 3 segundos cancela el intento pendiente y reconecta con el `CBPeripheral` más fresco visto - implementando el patrón "re-escanear antes de reconectar" de la sección 6, necesario porque el reintento interno de CoreBluetooth nunca le da a la app la oportunidad de usar una dirección actualizada.

**Verificado con hardware real: Android=víctima + iPhone=rescatista conecta, intercambia claves, e intercambia mensajes en ambos sentidos.**
Con esto, las dos direcciones de #64 (iOS-host+Android-rescatista, confirmada en una sesión anterior, y Android-host+iOS-rescatista, confirmada acá) quedan resueltas de punta a punta.
El resto de este documento (secciones 1-6) queda como registro del proceso de descarte - la sección 2 en particular documenta evidencia real y útil sobre cómo CoreBluetooth maneja conexiones que nunca completan, aunque la causa de fondo de Farosos resultó ser un bug propio, no el bug externo que esa sección investigaba.

---

## 1. Hipótesis de LE Extended Advertising: interop conocida iOS-central / Android-peripheral

### Hallazgos

**El nombre de los comandos HCI no prueba que el PDU sobre el aire sea extendido.**
El Bluetooth Core Spec (Vol 4, Part E, comando `LE Set Extended Advertising Parameters`) define el campo `Advertising_Event_Properties`, cuyo bit 4 es "Use legacy advertising PDUs".
Según la documentación técnica consultada (Nordic DevZone, hilo "How to perform Extended advertisements with an advertising set for legacy advertising PDUs"), cuando ese bit está en 1 el controlador sigue emitiendo PDUs legacy-equivalentes (`ADV_IND` y análogos) aunque el comando HCI usado sea de la familia "extended"; solo cuando el bit está en 0 se emiten PDUs verdaderamente extendidos (`ADV_EXT_IND` + `AUX_ADV_IND` en canal secundario), y en ese caso "the advertisement shall not be both connectable and scannable" (restricción del propio spec, citada en el mismo hilo).
Fuente: https://devzone.nordicsemi.com/f/nordic-q-a/93226/how-to-perform-extended-advertisements-with-an-advertising-set-for-legacy-advertising-pdus

Esto es reforzado por la doc de AOSP de requisitos HCI de Android (https://source.android.com/docs/core/connect/bluetooth/hci_requirements), que dice explícitamente sobre el parámetro vendor-specific que rastreaba instancias de advertising legacy: "This parameter is deprecated in the Google feature spec v0.98 and higher in favor of the LE Extended Advertising available in the BT spec version 5.0 and higher" - es decir, Google migró la implementación interna del stack Bluetooth de Android a usar la familia de comandos HCI "extended" como mecanismo base en controladores BT5+, independientemente de si la app llama a la API vieja (`AdvertiseSettings`) o la nueva (`AdvertisingSetParameters`).
No encontré el punto exacto del código fuente Java/nativo de AOSP que confirme esto línea por línea (el camino `BluetoothLeAdvertiser.startAdvertising()` en `frameworks/base` solo delega a `IBluetoothGatt.startMultiAdvertising()`, cuya implementación vive en el stack nativo `packages/modules/Bluetooth`, que no pude inspeccionar via fetch) - esto lo dejo como inferencia respaldada por dos fuentes primarias parciales, no como hecho confirmado línea de código.

**Implicación práctica para el diagnóstico**: los nombres de comando vistos en el `btsnoop_hci.log` (`LE Set Extended Advertising Parameters [v1]`, etc.) no bastan para concluir que Android emite PDUs extendidos sobre el aire.
Hace falta revisar el valor exacto del campo `Advertising_Event_Properties` dentro de ese comando capturado - específicamente el bit "legacy PDU" - antes de seguir esta hipótesis.
Si `tshark` lo decodifica como "Legacy" (bit = 1), la hipótesis de Extended Advertising queda descartada como causa raíz sin necesidad de cambiar nada de código.

**Confirmado (2026-08-20, releído del `btsnoop_hci.log` ya capturado en esta misma sesión): el bit está en "legacy".**
El comando `LE Set Extended Advertising Parameters` del advertising set real del chat (el que trae `Connectable: True`) decodifica como `Advertising Event Properties: 0x0013 = Use Legacy PDUs, Scannable, Connectable`.
El bit "Use Legacy PDUs" es `True`.
**Esta hipótesis queda descartada como causa raíz** - Android ya anuncia con PDUs legacy-equivalentes pese a usar los comandos HCI de la familia "extended"; no hace falta ni vale la pena probar `AdvertisingSetParameters.setLegacyMode(true)` explícito, porque el comportamiento ya es ese.

**Evidencia de interop rota sí existe, pero es sobre timing de conexión, no sobre "cero señal".**
El hilo de foro de Apple https://developer.apple.com/forums/thread/743372 ("BLE5 extended advertising not work...") documenta, contra un nRF52840 (Nordic) anunciando con extended advertising real: "I don't get a response to the connection from the BLE5 advertisement. This was confirmed by Wireshark capture", con "Apple devices violate timing values when they are trying to connect to a peripheral using the advertisement extension", y una confirmación cruzada de Nordic: "Nordic has confirmed that the issue is not on their side" y "We never saw the issue when we were trying to connect to the same peripheral with an Android phone or other BLE devices."
El workaround confirmado ahí es forzar legacy advertising: "As a test switching to BLE4 advertising, devices are both discoverable and connectable, on these same iPhone versions running iOS 17."
Ningún ingeniero de Apple respondió en ese hilo.
Importante: en ese caso el síntoma es un bucle de reconexión que eventualmente sí conecta ("this reconnecting loop breaks and the connection is established successfully"), no un atasco permanente sin ningún callback - un síntoma distinto al de Farosos, aunque en la misma familia de problema.

Un segundo hilo, https://developer.apple.com/forums/thread/778703 ("Inconsistent BLE Extended Advertising Scanning on iOS", iPhone 16 Pro, iOS 18), reporta detección inconsistente de paquetes extendidos ("Legacy advertising packets are scanned without any issues") sin respuesta de Apple y sin resolución.

### Try this / Probar esto

**Descartado - no probar.**
El bit ya está confirmado en "legacy" (ver arriba), así que forzar `AdvertisingSetParameters.setLegacyMode(true)` explícito no cambiaría nada; Android ya se comporta así.
Se deja documentado el snippet solo como referencia de qué se habría probado si el bit hubiera salido distinto:

```kotlin
val params = AdvertisingSetParameters.Builder()
    .setLegacyMode(true)
    .setConnectable(true)
    .setScannable(true)
    .build()
advertiser.startAdvertisingSet(params, advertiseData, scanResponse, null, null, callback)
```

### Veredicto de aplicabilidad para Farosos

**Descartada como causa raíz, confirmado con datos reales.**
El `Advertising_Event_Properties` del advertising set conectable real del chat ya decodifica como `0x0013 = Use Legacy PDUs, Scannable, Connectable` en el `btsnoop_hci.log` capturado el mismo día de esta investigación.
Android no está emitiendo PDUs extendidos sobre el aire pese a usar los comandos HCI de esa familia.
Esta vía queda cerrada; la atención debe ir a la sección 2.

---

## 2. Opciones de `CBConnectPeripheralOptionsKeys` para desatascar una conexión

### Hallazgos

Ninguna de las opciones documentadas de `connect(_:options:)` está descrita, ni por Apple ni por ningún ingeniero en foro, como un mecanismo para recuperar una conexión ya atascada en `.connecting`:

- `CBConnectPeripheralOptionEnableAutoReconnect` (iOS 17+): gestiona reconexión automática tras una desconexión ya ocurrida, no ayuda a completar una conexión que nunca arrancó.
  Un desarrollador reportó el error "One or more parameters were invalid" al usarla sin implementar el nuevo delegate `centralManager(_:didDisconnectPeripheral:timestamp:isReconnecting:error:)` - el fix fue implementar ese método, no algo relacionado al atasco.
  Fuente: https://developer.apple.com/forums/thread/741330
- `CBConnectPeripheralOptionStartDelayKey`, `CBConnectPeripheralOptionEnableTransportBridgingKey`, `CBConnectPeripheralOptionRequiresANCS`, `CBConnectPeripheralOptionNotifyOnConnectionKey`/`NotifyOnDisconnectionKey`/`NotifyOnNotificationKey`: la documentación de Apple (developer.apple.com/documentation/corebluetooth) los describe para casos de background-alert (los tres `NotifyOn*`), bridging a Bluetooth Classic ya conectado (transport bridging), requerir ANCS (accesorios tipo wearable con notificaciones), y un delay de arranque de conexión - ninguno documentado como relevante para un peripheral no-Apple/no-MFi que nunca produce respuesta.

**El hallazgo más importante de esta sección no es sobre las opciones de `connect()`, sino sobre el estado actual (2026) del bug de fondo.**
El hilo https://developer.apple.com/forums/thread/807938 ("CoreBluetooth connection never starts") documenta un síntoma prácticamente idéntico al de Farosos: "I then start a connection to the peripheral. I never get a callback to say the connection succeeded, failed, or disconnected."
Un sniffer BLE externo (hardware Mini-Moreph) confirmó "the iPhone never tried to connect to any of the peripherals" - pero el log HCI *interno* de iOS (obtenido, según el hilo, con la misma clase de herramienta que PacketLogger/perfil de debug de Bluetooth) sí mostró actividad invisible desde afuera: "a create connection request was sent, but a cancel connection request was sent 0.018 seconds later. No feedback was given to my application through CoreBluetooth."

Quinn "The Eskimo!" (ingeniero de Apple DTS) confirmó en enero de 2026 que el reporte de bug del usuario (`FB21100266`) "is marked as a dup of an internal bug. That bug is being investigated by the Bluetooth engineering team", y sobre workarounds: "I don't know enough about Bluetooth to offer any insights into that. I'm gonna ping a colleague about it. However, if no one follows up here then you should assume that there isn't a workaround."
Al mes de abril de 2026 el hilo seguía sin resolución.
No hay confirmación explícita en el hilo de que este bug interno sea el mismo del hilo iOS-17.x 742497, ni que aplique específicamente a iOS 26 - pero el patrón encaja de forma casi exacta con el síntoma de Farosos: **conexión que se cancela sola dentro del propio radio/daemon de iOS antes de llegar a la interfaz aérea, y por tanto invisible tanto para un sniffer externo (Mini-Moreph, o el HCI snoop de Android en el caso de Farosos) como para el propio delegate de la app.**

### Try this / Probar esto

No hay ningún cambio de API confirmado que desatasque la conexión.
La acción de mayor valor es **replicar el diagnóstico del hilo 807938**: capturar el log HCI del lado iOS (ver sección 5) durante la ventana de atasco, y buscar específicamente el patrón "create connection request seguido de cancel connection request en <100ms" dentro del propio iPhone.
Si aparece, es evidencia fuerte de que Farosos está pisando el mismo bug interno de Apple que `FB21100266`, y justifica: (a) archivar un Feedback Assistant propio referenciando ese mismo patrón y pidiendo que se linkee al bug interno, y (b) dejar de buscar la causa en el lado Android, porque el problema nunca llegaría a tocar el controlador Bluetooth de Android en absoluto - lo cual también explicaría por qué el HCI snoop de Android ya capturado no muestra nada.

### Veredicto de aplicabilidad para Farosos

**El hallazgo más accionable de toda la investigación.**
No existe una opción de `connect()` que resuelva el atasco, pero sí existe evidencia primaria (respuesta directa de un ingeniero DTS de Apple, enero 2026) de un bug interno activo y sin workaround conocido, con un mecanismo descrito (cancelación de conexión dentro de ~20ms, invisible fuera del propio iPhone) que coincide con el síntoma de "cero actividad HCI del lado Android" mejor que cualquier otra hipótesis investigada en este documento.
La siguiente acción de mayor prioridad de todo este research es replicar esa captura interna con PacketLogger para confirmar o descartar que Farosos está pisando exactamente este bug.

---

## 3. Cambios de CoreBluetooth específicos de iOS 26

### Hallazgos

No se encontraron notas de release oficiales de Apple para iOS 26 (ni sus puntos 26.3/26.4/26.5/26.6) que mencionen cambios al comportamiento de establecimiento de conexión de Core Bluetooth.
El único cambio CoreBluetooth-relacionado con iOS 26 documentado y confirmado (Technical Note TN3115, discutido en https://developer.apple.com/forums/thread/806013) es sobre *relanzamiento en background tras force-quit o toggle del botón Bluetooth del Centro de Control*, no sobre conexión en foreground: "Starting in iOS 26 and iPadOS 26, only apps that use AccessorySetupKit to setup Bluetooth accessories will be relaunched" en esos dos escenarios específicos.
Un ingeniero de Apple aclaró explícitamente que esto **no** es una regresión: "An app that was force quit was never able to be relaunched before. iOS 26 actually adds the capability for such apps to be launched if they opt in to use AccessorySetupKit" - es decir, no resta capacidad, solo la añade condicionalmente en un caso donde antes no existía.
No aplica al escenario de Farosos (conexión iniciada en foreground, app no forzada a cerrar).

Otro hilo, https://developer.apple.com/forums/thread/809741, tiene confirmación directa de un ingeniero Staff de Apple de que **no hay cambios mayores** de background Bluetooth entre iOS 18 e iOS 26: "there are no major differences in iOS 26 that will prevent apps being able to perform Bluetooth operations in the background any more than iOS 18."

Sí hay evidencia de que el stack BLE de bajo nivel de iOS 26 tuvo cambios de comportamiento no documentados en release notes: el hilo https://developer.apple.com/forums/thread/806328 reporta que "the iOS Bluetooth host repeatedly sends different Control Opcode: LL_CONNECTION_UPDATE_IND to the peripheral, updating approximately every 100ms" específicamente en iOS 26 contra un chip nRF52832 con perfiles HID+MIDI, causando desconexión con error 0x28.
El ingeniero de Apple (Argun Tekant, WWDR Engineering) no confirmó causa raíz, solo pidió el flujo estándar de diagnóstico (bug report + logs + sniffer + sysdiagnose).
Esto no prueba una relación directa con el bug de Farosos, pero sí confirma que el stack de conexión BLE de iOS 26 tiene comportamiento nuevo y activamente bajo investigación en al menos otro caso no relacionado, reforzando que "es iOS 26" no puede descartarse solo porque no está en las release notes oficiales.

### Fuente primaria

- https://developer.apple.com/documentation/ios-ipados-release-notes/ios-ipados-26-release-notes y sus puntos .3/.4/.5/.6 - sin contenido de Bluetooth accesible via fetch (la página es un índice cargado con JS); búsqueda dirigida tampoco encontró texto citable de Bluetooth en release notes oficiales.
- https://developer.apple.com/forums/thread/806013 - TN3115, aclaración de ingeniero de Apple sobre AccessorySetupKit y relanzamiento en background.
- https://developer.apple.com/forums/thread/809741 - confirmación de ingeniero Staff de que no hay cambios mayores de background BLE en iOS 26 vs 18.
- https://developer.apple.com/forums/thread/806328 - bug de conexión BLE específico de iOS 26 (no relacionado directamente, pero evidencia de cambios internos activos).

### Veredicto de aplicabilidad para Farosos

**No confirmado como causa, pero tampoco descartable.**
No hay evidencia documentada de un cambio de API o comportamiento de conexión que explique el atasco.
Sin embargo, no pude verificar las release notes oficiales directamente (la página de Apple no expone el contenido a fetch automatizado sin JS), así que esta sección queda con una laguna real: alguien con acceso de navegador debería revisar manualmente developer.apple.com/documentation/ios-ipados-release-notes/ios-ipados-26-release-notes buscando "Bluetooth" antes de descartar esta vía por completo.
La evidencia indirecta (hilo 806328, hilo 807938 con actividad de enero-abril 2026) sí sugiere que el stack BLE de iOS tiene comportamiento activo y cambiante en este periodo, consistente con la hipótesis de que Farosos está viendo una manifestación más de ese mismo periodo de inestabilidad, no necesariamente ligada a la versión 26 específicamente.

---

## 4. Configuración de `AdvertiseSettings` de Android como causa de fallo silencioso en iOS

### Hallazgos

**No encontré ninguna fuente primaria (Apple, Android, Bluetooth SIG, o vendor de stack BLE con detalle de ingeniería real) que documente que la ausencia de Local Name o de TX Power Level en el advertisement cause que un central iOS falle en completar una conexión sin invocar ningún callback.**
Esto se investigó explícitamente y no se encontró respaldo.
Lo que sí confirma la documentación oficial de Android (https://source.android.com/docs/core/connect/bluetooth/ble_advertising) es que `AdvertiseSettings`/`AdvertiseData` soportan `setConnectable(true)`, niveles de TX power, y `setIncludeDeviceName(true)`, pero **"no contiene advertencias documentadas de interop con iOS u otras plataformas"** - la propia doc de AOSP solo remite a pruebas de conformidad Bluetooth 5 en general, sin mención de iOS.

La única variable de configuración con respaldo primario real y relevancia directa es el flag *connectable* del advertisement (`setConnectable`), ya que sin él la conexión ni siquiera debería ser intentable por ningún central - pero esto ya está descartado como causa en el caso de Farosos, dado que la conexión sí se inicia (`state` pasa a `.connecting`) y el problema ocurre después de eso, no en el descubrimiento.

Encontré, pero no puedo confirmar como aplicable, un dato de una discusión de terceros (no primaria, GitHub issue de una librería, no un vendor reconocido) sobre diferencias de dónde vive el nombre del dispositivo entre plataformas ("iOS uses Device Name characteristic 0x2a00 on Generic Access Service 0x1800... Android uses GAP Local Name (0x09)... iOS does not expose the GAP service"), pero esto es sobre el *nombre mostrado*, no sobre si la conexión se completa - no lo incluyo como evidencia de causa, solo lo documento para que quede descartado explícitamente como pista.

### Fuente primaria

- https://source.android.com/docs/core/connect/bluetooth/ble_advertising - confirma ausencia de advertencias de interop documentadas para esta combinación de flags.

### Veredicto de aplicabilidad para Farosos

**Sin respaldo primario, no perseguir esta vía como prioridad.**
A diferencia de las secciones 1 y 2, aquí la búsqueda activa no encontró ninguna fuente confiable que conecte la configuración de `AdvertiseSettings` (omisión de Local Name/TX power) con fallos silenciosos de conexión en iOS.
Dado que el patrón descrito en la sección 2 (bug interno de Apple con cancelación de conexión en <20ms) no depende en absoluto del contenido del advertisement, y que Android-central sí conecta sin problema contra el mismo advertisement de Farosos, esta hipótesis tiene baja probabilidad relativa: cualquier problema de contenido del advertisement debería, en teoría, también afectar el *descubrimiento*, no específicamente la fase de conexión post-descubrimiento.

---

## 5. Herramientas de diagnóstico oficiales de Apple: PacketLogger

### Hallazgos

Existe un flujo oficial de Apple, documentado también por un post del blog oficial de Bluetooth SIG (que describe explícitamente herramientas de Apple), para capturar el trace HCI interno del propio iPhone durante el bug - el gap real que esta investigación no ha cubierto hasta ahora, ya que todo el diagnóstico existente (issue #64) es del lado Android únicamente.

Pasos confirmados (fuente: https://www.bluetooth.com/blog/a-new-way-to-debug-iosbluetooth-applications/, post oficial del blog de Bluetooth SIG):

1. **Instalar el perfil de logging de Bluetooth en el iPhone**: visitar `https://developer.apple.com/bug-reporting/profiles-and-logs/?name=bluetooth` desde el navegador del propio iPhone, descargar el perfil bajo "Bluetooth for iOS", autenticar con la cuenta de Apple Developer, e instalarlo desde Ajustes → Perfil descargado.
2. **Descargar "Additional Tools for Xcode"** desde developer.apple.com (requiere cuenta de Apple Developer), extraer el `.dmg`, y localizar `PacketLogger.app` dentro de la carpeta Hardware; arrastrarlo a Aplicaciones.
3. **Conectar el iPhone al Mac por cable** y abrir PacketLogger → File → New iOS Trace.
   La app empieza a trazar toda la actividad Bluetooth del dispositivo con el perfil instalado; un ícono de pulso en la esquina superior del iPhone confirma que la traza está activa.
4. **Reproducir el bug** (intentar la conexión de chat contra el host Android) mientras la traza corre.
5. PacketLogger soporta decodificación de protocolo (Bluetooth SIG y protocolos propietarios de Apple), filtrado avanzado con regex, anotación/flagging de paquetes, y **exportación a formato compatible con Wireshark/btsnoop** para análisis cruzado con el log ya capturado del lado Android.

Esto requiere literalmente a un humano frente al teclado con el iPhone físico conectado por cable al Mac - no es scriptable remotamente ni automatizable desde este entorno de research.

### Fuente primaria

- https://www.bluetooth.com/blog/a-new-way-to-debug-iosbluetooth-applications/ - guía completa citada arriba, post oficial del blog de Bluetooth SIG (no Apple directamente, pero documenta el flujo de herramientas propias de Apple con pasos verificables).
- https://developer.apple.com/bug-reporting/profiles-and-logs/?name=bluetooth - URL oficial de Apple para el perfil de logging de Bluetooth, referenciada también dentro del hilo 806328 por el propio ingeniero de Apple como parte del flujo estándar de diagnóstico que pide para cualquier bug de Bluetooth reportado.

### Veredicto de aplicabilidad para Farosos

**Esta es la acción de diagnóstico con mayor probabilidad de cerrar la brecha de evidencia actual, y está totalmente respaldada por fuente primaria.**
El hilo 807938 (sección 2) muestra el mismo tipo de captura revelando el mecanismo real del bug (cancelación interna en <20ms) cuando un sniffer externo o el log de la otra punta no ve nada - exactamente la situación de Farosos hoy.
Requiere sesión con hardware físico (Mac + iPhone por cable), así que no es ejecutable dentro de este research, pero debe ser el siguiente paso de campo antes de seguir especulando sobre causa raíz.

---

## 6. Enfoques alternativos documentados para conectar un central iOS contra un peripheral GATT Android

### Hallazgos

**Patrón confirmado por un ingeniero de Apple (foro oficial): re-escanear y conectar contra una instancia recién descubierta, en vez de reintentar `connect()` sobre el mismo `CBPeripheral`.**
En https://developer.apple.com/forums/thread/799182, ante una pregunta sobre reconexión tras un peripheral que se apaga y prende, la recomendación citada textualmente del ingeniero de Apple es: "In earlier iOS versions you would request a reconnection directly in your code. When you get the callback for disconnection you can at that point issue a connectPeripheral() command to the CBPeripheral that has just disconnected. If you don't have a CBPeripheral, you can also always start a scan in the disconnect callback, and connect after re-discovering the device."
Esto es sobre reconexión tras un disconnect, no sobre un atasco sin callback, pero confirma que el patrón oficialmente respaldado por Apple para conexiones poco fiables es "re-descubrir, no reintentar sobre el mismo objeto" - relevante porque Farosos ya podría estar reintentando sobre el mismo `CBPeripheral` atascado en vez de forzar un nuevo ciclo de scan.

**MFi no aplica; GATT estándar es explícitamente soportado sin certificación.**
Confirmado por un ingeniero de Apple (Scott) en https://developer.apple.com/forums/thread/705848: "Bluetooth low energy accessories do not interface with the External Accessory framework and are not required to be MFi compliant" - el problema de Farosos no tiene relación con requisitos de certificación de hardware.

**No encontré ninguna guía de interoperabilidad first-party de Bluetooth SIG, ni ninguna nota oficial de developer.android.com, que documente quirks conocidos específicos "Android-peripheral vs iOS-central".**
Busqué activamente y no hay una fuente equivalente a una "guía de interop cross-platform" citable - lo digo explícitamente en vez de inventar una referencia.
La evidencia más cercana a esto es indirecta: el propio desarrollador del hilo 807938, frustrado por la falta de workaround, comentó sarcásticamente "I guess the only workaround I can offer to affected customers is to use an Android device instead?" - lo cual, sin ser una fuente de "mejores prácticas", es una señal informal de que developers en la misma situación no han encontrado alternativa distinta a evitar iOS como central contra ciertos peripherals no-Apple.

### Fuente primaria

- https://developer.apple.com/forums/thread/799182 - patrón oficial de "re-scan y conectar sobre instancia nueva" recomendado por ingeniero de Apple.
- https://developer.apple.com/forums/thread/705848 - confirmación de que GATT estándar no requiere MFi.

### Veredicto de aplicabilidad para Farosos

**Vale la pena probar el patrón de re-scan como mitigación de bajo costo, pero no hay evidencia de que resuelva el atasco actual (que ocurre antes de cualquier posible reintento, en el primer `connect()`).**
Si el diagnóstico de PacketLogger (sección 5) confirma el patrón de auto-cancelación interna en <20ms del hilo 807938, ningún patrón de reintento a nivel de aplicación va a ayudar, porque el problema ocurre dentro del propio stack de iOS antes de que la app tenga oportunidad de reaccionar - el `connect()` "falla" sin siquiera producir un evento que un `retry`-loop pueda escuchar.

---

## Próximos pasos, priorizados

Orden por (a) respaldo de fuente primaria y (b) costo de ejecutar:

1. ~~Releer el `btsnoop_hci.log` ya capturado y confirmar el bit "legacy PDU"~~ - **hecho, el mismo día de esta investigación.**
   El bit ya está en "legacy" (`0x0013`).
   Hipótesis de Extended Advertising descartada como causa raíz - ver sección 1.
2. **Capturar un trace de PacketLogger del lado iOS durante el atasco** (sección 5) y buscar el patrón "create connection request seguido de cancel connection request en <100ms" documentado en el hilo 807938.
   Costo: requiere Mac + iPhone por cable + humano al teclado, pero es la única vía primaria confirmada para ver qué hace el radio de iOS, que hasta ahora es una caja negra total en este research.
   **Con la sección 1 ya descartada, este es ahora el siguiente paso de mayor prioridad.**
3. **Si el paso 2 confirma el patrón del hilo 807938**, archivar un Feedback Assistant propio referenciando `FB21100266` y pidiendo que se vincule al mismo bug interno - no hay workaround documentado, pero sumar un caso reproducible ayuda a que Apple lo priorice, y es la única vía formal de obtener una respuesta oficial más allá de "no hay workaround conocido" (respuesta ya dada por DTS en enero 2026).
4. ~~Probar el cambio a `AdvertisingSetParameters.Builder().setLegacyMode(true)`~~ - **descartado, no ejecutar.**
   El paso 1 ya confirmó que el bit actual está en modo legacy; este cambio no tendría ningún efecto.
5. **Revisar manualmente las release notes de iOS 26 (todas las sub-versiones) en el navegador**, buscando "Bluetooth" - esta investigación no pudo confirmar ni descartar cambios documentados porque la página no expone contenido a fetch automatizado (sección 3).
   Costo bajo, cierra una laguna real de esta investigación.
6. **Adoptar el patrón de "re-scan antes de reconectar"** (sección 6) como higiene general del código de `ChatCentralConnection`, independientemente de si resuelve este bug específico - es la práctica recomendada por Apple para conexiones poco fiables y no tiene costo de implementación significativo.

No recomiendo perseguir la hipótesis de la sección 4 (configuración de `AdvertiseSettings` como causa) como línea de investigación activa: no tiene respaldo primario y hay una explicación alternativa (sección 2) mejor sustentada por una fuente oficial de Apple.
