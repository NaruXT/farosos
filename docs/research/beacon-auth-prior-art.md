# Prior art: autenticación de beacons en redes desconectadas/constrained

Investigación de referencia para el problema de "cualquiera con un sniffer BLE
puede falsificar un beacon `AYUDA_SOLICITADA` u `OK` a nombre de otro
dispositivo" (ver contexto en `spec/packet-format.md` y decisión 4/13 de ese
documento sobre el límite de 31 bytes de BLE legacy advertising). No es spec,
es investigación de apoyo para una fase futura.

Dirección ya asumida como punto de partida (no re-derivada aquí):
Ed25519 por dispositivo, `device_id_hash = hash(pubkey)` en vez de
`hash(UUID instalación)`, y dos casos de participante — **Caso A** ("nunca
registrado", sin conectividad jamás, fragmenta firma+pubkey completos a
través de varios beacons de 26 bytes) y **Caso B** ("pre-registrado", cadena
de hashes estilo S/KEY donde cada beacon revela un eslabón atado al campo
`Secuencia` existente).

Restricciones exactas contra las que se evalúa cada tecnología: payload de
26 bytes (30 con envoltorio BLE), dentro del límite duro de 31 bytes de BLE
**legacy advertising** (límite de la especificación Bluetooth Core, no una
política de SO — ver decisión 4 en `spec/packet-format.md`); ningún beacon
puede depender de un handshake en vivo; conviven participantes Caso A y
Caso B en la misma malla.

---

## 1. Bluetooth Mesh Profile (Bluetooth SIG)

### Mecanismo

Bluetooth Mesh tiene una jerarquía de tres claves: **NetKey** (clave de red,
la posesión de una NetKey es lo que hace a un nodo miembro de esa red/subred),
**AppKey** (clave de aplicación, atada matemáticamente a una NetKey específica,
separa la seguridad de capa de red de la seguridad de capa de aplicación para
que un relay pueda reenviar sin poder leer el payload de aplicación), y
**DevKey** (clave única por nodo, usada solo para aprovisionamiento/configuración,
nunca viaja por la red).

La autenticación de mensajes en el transporte usa AES-CCM: "AES-CCM is used
as the basic encryption and authentication function in all cases", produciendo
un Message Integrity Check (MIC) de 4 u 8 bytes en capa de red, combinado con
otro MIC de 4 u 8 bytes en capa de aplicación (fuente: blog oficial de
Bluetooth SIG).

El punto crítico para Farosos es **cómo llegan las claves a los nodos antes de
poder usar ese MIC liviano**: el aprovisionamiento (`provisioning`) — el paso
donde un nodo nuevo recibe su NetKey/AppKey/DevKey — se hace sobre un
**bearer con conexión**: `PB-ADV` (Provisioning Bearer sobre paquetes de
advertising, pero como una sesión de mensajes segmentados con estado, no un
beacon suelto) o `PB-GATT` (una conexión GATT real, para centrales como
teléfonos que no soportan el bearer de advertising crudo). El documento de
Nordic/Bluetooth confirma: "PB-GATT uses Proxy protocol (GATT bearer) so a
BLE connection needs to be established. This feature allows Bluetooth LE
devices... such as mobile phones, to act as provisioners." Antes de
aprovisionar, el nodo sin aprovisionar emite beacons de advertising
no-conectables solo para anunciar "existo, aprovisióname" — no llevan ningún
payload de aplicación autenticado.

### Fuente primaria

- https://www.bluetooth.com/blog/bluetooth-mesh-security-overview/ — jerarquía de claves y AES-CCM/MIC.
- https://www.bluetooth.com/blog/provisioning-a-bluetooth-mesh-network-part-1/ y documentación de bearers PB-ADV/PB-GATT (corroborado también en la FAQ técnica de Nordic Semiconductor, fabricante de referencia de chips BLE, y Ezurio) — confirma que el aprovisionamiento requiere un bearer con estado (secuencia de PDUs) o una conexión GATT real, no un beacon aislado de 31 bytes.
- Nota: el PDF oficial "Mesh Security Overview INFO v1.0" (bluetooth.com/wp-content/uploads/2025/04/MeshSecurityOverview_INFO_v1.0-1.pdf) es la fuente canónica completa; el blog post citado arriba resume su contenido en prosa accesible y fue la fuente efectivamente fetcheada.

### Veredicto de aplicabilidad para Farosos

**No transferible directamente, y confirma por qué el problema de Farosos es
genuinamente distinto.** Bluetooth Mesh nunca resuelve "autenticar un mensaje
de 26 bytes sin ningún intercambio previo" — lo evita por diseño: exige un
paso de aprovisionamiento con estado (varios PDUs encadenados, o una conexión
GATT completa) *antes* de que exista ninguna clave con la que producir un MIC.
Una vez aprovisionado, el MIC de 4-8 bytes que usa después sí es un buen
ejemplo de "autenticación barata que cabe en un payload chico" — pero
presupone exactamente la fase de intercambio de claves en vivo que el Caso A
de Farosos (el sobreviviente que *nunca* tuvo conectividad, ni con otro
teléfono via GATT antes del sismo) no puede dar por sentada. Confirma que la
estrategia de Farosos de "fragmentar firma+pubkey a través de varios beacons
pasivos" está resolviendo un problema que Bluetooth Mesh delega a GATT/PB-ADV
con estado — Farosos no tiene ese lujo porque no puede asumir que el otro
lado del handshake esté ahí en el momento.

---

## 2. DTN — Bundle Protocol (RFC 9171) y BPSec (RFC 9172)

### Mecanismo

RFC 9171 define el formato de "bundle" para redes con enlaces de altísima
latencia/desconexión (diseño con origen en JPL/NASA para IPN), pero
deliberadamente **no** define la autenticación dentro de sí mismo: "The
Bundle Protocol security architecture and the available security services
are specified in an accompanying document, the Bundle Protocol Security
(BPSec) specification" (RFC 9171 §8). La regla operativa es: "the node that
receives a bundle should verify its authenticity and validity before
operating on it in any way" (§4.2.5.1.1) — la verificación ocurre en cada
nodo receptor de forma asíncrona, sin necesitar contacto en vivo con el
origen.

BPSec (RFC 9172) aporta el mecanismo concreto: el **Block Integrity Block
(BIB)** — un bloque de extensión que lleva una firma/MAC sobre otro bloque
del bundle ("A BIB is a BP extension block... to ensure the integrity of its
plaintext security target(s)", §3.7) — y el **Block Confidentiality Block
(BCB)** para cifrado autenticado (AEAD obligatorio, §3.8).

Dos puntos son directamente relevantes a la pregunta abierta de Farosos sobre
relays que nunca tuvieron conectividad:

- **Gestión de claves fuera de alcance, a propósito**: "BPSec assumes that
  key management is handled as a separate part of network management"
  (RFC 9172 §6) — el RFC no prescribe cómo un nodo obtiene las claves para
  verificar, dejando la puerta abierta a esquemas asimétricos donde la clave
  pública basta (compatible con el enfoque Ed25519 de Farosos).
- **Autenticación end-to-end, no hop-by-hop, y verificación opcional en
  tránsito**: "Hop-by-hop authentication is NOT a supported security service
  in this specification" (§1.1). Un nodo intermedio que no es el
  "security acceptor" designado *puede* intentar verificar si tiene el
  material criptográfico, pero no está obligado ni garantizado a poder
  hacerlo (§5.1.2) — es decir, BPSec acepta explícitamente que un relay
  intermedio puede quedarse sin poder verificar, y eso no rompe el protocolo:
  simplemente reenvía el bundle firmado tal cual, y la verificación ocurre
  quien sea que sí tenga la clave pública (típicamente el destino final).

### Fuente primaria

- https://www.rfc-editor.org/rfc/rfc9171 — §8 (delegación a BPSec), §4.2.5.1.1 (obligación de verificar al recibir), §3.1/§5.2 (retention constraints, store-and-forward).
- https://www.rfc-editor.org/rfc/rfc9172 — §3.7 (BIB), §3.8 (BCB/AEAD), §6 (gestión de claves fuera de alcance), §1.1 y §5.1.2 (autenticación end-to-end, no hop-by-hop; verificación intermedia opcional/no garantizada).

### Veredicto de aplicabilidad para Farosos

**El más directamente transferible de los 5, conceptualmente.** BPSec es
exactamente el patrón que Farosos ya está usando de facto: (a) el payload
lleva su propia prueba criptográfica (firma) en vez de depender de un canal
seguro; (b) un relay que no puede verificar reenvía igual — no bloquea la
propagación; (c) la verificación real ocurre "cuando alguien con las claves
correctas lo vea", que en Farosos es exactamente el gateway con conectividad.
Esto **confirma explícitamente** (no solo por ausencia de alternativa) que
"un relay sin conectividad reenvía sin poder verificar, y la verificación
real ocurre río abajo" es un patrón de diseño reconocido en DTN, no una
concesión de mala gana. La diferencia importante: BPSec asume bundles de
tamaño arbitrario (una firma Ed25519 de 64 bytes es trivial ahí); el
problema real de Farosos — cómo empaquetar esa misma idea dentro de 26 bytes
por paquete — es un problema de *framing/fragmentación* que BPSec no
necesita resolver y por tanto no aporta receta para eso (de ahí que la
fragmentación entre varios beacons sea una adaptación necesaria, no algo
que tomar prestado).

---

## 3. Meshtastic

### Mecanismo

Meshtastic separa dos capas de cifrado con propósitos distintos:

- **Canal (broadcast, PSK compartida)**: "AES256-CTR encryption for the
  payload of each packet when sending via LoRa, with a different key for
  each channel" — esto es cifrado *simétrico* de canal (todo el que tenga la
  PSK puede leer y también *falsificar* el campo de remitente: la doc propia
  de límites conocidos dice que para mensajes de canal "sender field is
  indicative, and anyone with access to the channel key can trivially lie").
  Es decir: el modo de canal por defecto **no autentica al remitente**, solo
  da confidencialidad de grupo — el mismo problema de fondo que tiene hoy
  Farosos sin ninguna capa nueva.
- **Mensajes directos (PKC, firmware 2.5+)**: cada nodo tiene un par de
  claves Curve25519/X25519 propio; los mensajes directos usan intercambio
  Diffie-Hellman X25519 para derivar un secreto y firman con la clave
  privada del remitente, lo que sí da autenticación de remitente: "messages
  are signed with the sender's private key, allowing the recipient to
  verify the sender's identity."

No hay ningún registro central ni pre-registro de nodos: el modelo de
confianza es enteramente por posesión de clave (peer-to-peer), sin mención de
autoridad central o servidor de pre-registro en la documentación de
encriptación.

### Fuente primaria

- https://meshtastic.org/docs/overview/encryption/ — AES256-CTR por canal, PKC por DM con X25519, firma con clave privada del remitente.
- https://meshtastic.org/docs/about/overview/encryption/limitations/ — admite explícitamente que el modo de canal no autentica al remitente ("anyone with access to the channel key can trivially lie").

### Veredicto de aplicabilidad para Farosos

**Confirma el diagnóstico del problema, pero su solución (PKC en DM) no
aplica al beacon broadcast de Farosos tal cual.** El modo "canal" de
Meshtastic es estructuralmente idéntico al beacon de Farosos hoy — grupo
compartido, sin autenticación de remitente, admitido como limitación
conocida — así que no aporta una salida nueva para ese caso. El modo PKC que
sí autentica requiere un intercambio de claves punto a punto (X25519 DH) que
asume una sesión/mensaje dirigido, no un beacon suelto que cualquiera puede
escuchar sin haber "hablado" antes con el emisor — es decir, tiene la misma
limitación de fondo que Bluetooth Mesh: autenticación fuerte solo después de
intercambio de claves, no en el primer paquete no solicitado. Sí es
consistente con — y refuerza — la elección de Farosos de usar clave pública
por dispositivo (Ed25519) en vez de secreto compartido, ya que Meshtastic
también evolucionó de PSK de grupo hacia claves asimétricas por nodo
precisamente para poder autenticar sin coordinación previa entre esas dos
partes específicas.

---

## 4. Briar

### Mecanismo

Briar separa el problema en protocolos independientes del stack "Bramble":

- **BHP (Bramble Handshake Protocol)** — el handshake en sí **no** resuelve
  la confianza inicial; la delega explícitamente a un paso anterior fuera de
  su alcance: "Before two peers can communicate using BHP they must exchange
  long-term public keys. BHP does not specify how this should be done...
  Any synchronous or asynchronous communication channel can be used." En la
  práctica (fuera del texto de esta spec pero es el diseño conocido de
  Briar) ese intercambio ocurre vía código QR o link compartido en persona o
  por un canal ya de confianza — es decir, Briar exige un paso de
  verificación fuera de banda antes de que exista ningún contacto. La spec
  también advierte que sin eso, BHP es vulnerable a MITM: "If the adversary
  intercepted the prior exchange of long-term public keys and replaced them
  with its own public keys then BHP does not detect or prevent man-in-the-
  middle attacks."
- **BSP (Bramble Synchronisation Protocol)** — la sincronización
  store-and-forward entre pares intermitentemente conectados, con manejo de
  reintento por backoff exponencial y seguimiento de qué mensajes ya vio
  cada peer ("Offer any messages that the device is sharing with the peer,
  and does not know whether the peer has seen"). Requiere una capa de
  transporte segura por debajo ("BSP requires a transport layer security
  protocol" que garantice confidencialidad/integridad/autenticidad/forward
  secrecy) — es decir, BSP asume que la autenticación *ya* se resolvió en
  una capa anterior (el handshake BHP par a par), no algo que resuelva por
  sí mismo entre desconocidos.

### Fuente primaria

- https://code.briarproject.org/briar/briar-spec/-/raw/master/protocols/BHP.md — cita textual sobre intercambio previo de claves largo plazo fuera de alcance de BHP, y advertencia de MITM.
- https://code.briarproject.org/briar/briar-spec/-/raw/master/protocols/BSP.md — mecanismo de sincronización store-and-forward, dependencia de una capa de transporte segura ya autenticada.

### Veredicto de aplicabilidad para Farosos

**No aplica al problema central de Farosos — Briar resuelve un problema
distinto (contactos conocidos, mensajería dirigida) y depende de un paso
fuera de banda que un beacon broadcast anónimo no tiene.** Briar está
diseñado para relaciones de "contacto" explícitas entre dos personas que se
conocen y hacen un intercambio de claves verificado (QR en persona, por
ejemplo) — el equivalente en Farosos sería que dos sobrevivientes se
autentiquen mutuamente antes del sismo, lo cual contradice el escenario
central del Caso A (el dispositivo nunca tuvo esa oportunidad con nadie).
Donde Briar sí es un espejo útil es en confirmar el patrón general
"autenticación fuerte == requiere intercambio previo de claves (en persona,
QR, o vía servidor)" que aparece en los tres casos anteriores — refuerza que
no existe una tecnología de las investigadas que autentique un primer
contacto broadcast *sin* alguna forma de compromiso/registro previo (que es
justo lo que separa el Caso A del Caso B en el diseño ya asumido de
Farosos).

---

## 5. Secure Scuttlebutt (SSB)

### Mecanismo

La identidad en SSB **es** el par de claves: "An identity is an Ed25519 key
pair and typically represents a person, a device, a server or a bot" — mismo
patrón self-certifying que Farosos ya adoptó para `device_id_hash`. Cada
identidad mantiene un **feed** (log firmado, append-only): "The messages in
a feed form an append-only log... Each message (except the first one)
references the ID of the previous message, allowing a chain to be
constructed back to the first message in the feed" — un encadenamiento por
hash-de-mensaje-anterior conceptualmente análogo (no idéntico) a una cadena
de hashes estilo S/KEY.

La verificación de un mensaje de una identidad con la que un peer nunca habló
directamente no requiere contacto con el autor: cualquier peer que posea la
clave pública del autor puede verificar la firma de un mensaje reenviado por
un tercero ("To verify the signature, first remove the signature field...
verify [it] using the author's public key"). El descubrimiento de mensajes
nuevos ocurre por *gossip* entre pares conectados vía `createHistoryStream`
— la primera vez que un peer ve una identidad nueva, la spec no define un
mecanismo de confianza inicial más allá de la propia verificación
criptográfica: "no coordination or permission is required to create a new
[identity]" (creación libre de identidades, sin autoridad central) — lo cual
implica que SSB traslada el problema de "¿debería confiar en este
desconocido?" a las apps cliente / curación social (a quién sigues), no al
protocolo base.

### Fuente primaria

- https://ssbc.github.io/scuttlebutt-protocol-guide/ — definición de identidad como par Ed25519, estructura de feed encadenado, verificación de firma con la clave pública del autor, réplica vía `createHistoryStream`, y creación de identidad sin coordinación central.

### Veredicto de aplicabilidad para Farosos

**El más cercano en espíritu al Caso B (cadena de hashes), y confirma la
idea de "verificar contenido reenviado por terceros solo con lo que ya
tienes + clave pública del autor".** El patrón "cada mensaje se autoverifica
con la firma y la clave pública del autor, sin necesitar hablar con el
autor" es exactamente lo que Farosos necesita para que un relay reenvíe un
beacon Caso A/B sin conectividad. Pero el mecanismo de bootstrap de SSB para
"¿tengo la clave pública de este autor que nunca vi?" no es gratis: depende
de haber recibido esa clave pública antes (por gossip previo, invitación a
un pub, o conexión directa) — SSB no resuelve "verificar la primerísima vez
que ves esta identidad sin haber visto nunca su clave pública", que es
precisamente el problema del Caso A de Farosos (por eso Farosos fragmenta la
clave pública *dentro* del propio flujo de beacons, algo que SSB no necesita
hacer porque sus mensajes no están limitados a 26 bytes). Para el Caso B,
SSB no aporta mecanismo nuevo más allá de lo que Farosos ya diseñó
(hash-chain con `Secuencia` como índice) — el encadenamiento de SSB resuelve
un problema distinto (orden/integridad de un log largo), no autenticación
compacta de un valor único revelado por beacon.

---

## Recomendaciones para Farosos

**Nada de lo investigado cambia la dirección de fragmentación (Caso A) /
cadena de hashes (Caso B) ya en curso — la refuerza y aporta vocabulario
para justificarla, con un ajuste de expectativas concreto.**

1. **La pregunta abierta queda confirmada, no resuelta**: un relay sin
   conectividad no puede verificar beacons Caso B en tiempo real, y eso no
   es una limitación a resolver sino el patrón de diseño esperado en toda
   red de este tipo. Las tres tecnologías con modelo de confianza asimétrico
   (BPSec, Meshtastic PKC, SSB) coinciden en el mismo patrón: **un mensaje
   lleva su propia prueba criptográfica; el nodo que la reenvía no necesita
   verificarla para reenviarla; la verificación ocurre en quien sí tenga las
   claves/commitments correctos**. BPSec lo dice de forma más explícita y
   normativa que ninguna otra fuente ("hop-by-hop authentication is NOT a
   supported security service... [intermediate nodes] MAY attempt to verify
   ... only if" tienen el material). Esto confirma que diferir la
   verificación del Caso B al gateway/backend no es una carencia de diseño:
   es la respuesta correcta y es la misma que adopta la literatura DTN para
   el mismo tipo de restricción (enlaces de altísima latencia/desconexión).
   Vale la pena que el ADR futuro de este esquema cite RFC 9172 §1.1/§5.1.2
   explícitamente como respaldo de esa decisión, en vez de presentarla solo
   como concesión pragmática.

2. **Ningún caso investigado logra autenticar un "primer contacto" sin algún
   compromiso previo** (clave pública ya conocida, PSK de canal, o
   intercambio fuera de banda) — Bluetooth Mesh exige aprovisionamiento con
   conexión, Briar exige intercambio de claves fuera de banda con
   advertencia explícita de vulnerabilidad MITM si se omite, Meshtastic solo
   autentica DMs tras DH, SSB solo verifica identidades cuya clave pública
   ya llegó por otro canal. Esto es evidencia adicional (no solo ausencia de
   alternativa) de que el diseño del Caso A de Farosos — fragmentar la
   propia clave pública + firma dentro de los beacons, en vez de asumir que
   el verificador ya la tiene — es la única estrategia coherente con "cero
   compromiso previo, cero conectividad, solo broadcast pasivo". Ningún
   sistema investigado necesitaba resolver ese problema exacto porque todos
   asumen al menos una forma de intercambio inicial (aprovisionamiento,
   pairing QR, o servidor); Farosos es más restrictivo que los cinco.

3. **Matiz a incorporar sobre el Caso A**: mientras no se hayan recibido
   *todos* los fragmentos de firma+pubkey de un dispositivo, un relay
   Farosos está en una posición estructuralmente idéntica a un nodo BPSec
   que "no es el security acceptor y no tiene el material criptográfico" —
   debería reenviar los fragmentos igual (sin intentar ni fingir verificar
   nada), exactamente como BPSec permite. Esto sugiere una regla de
   implementación concreta y ya justificable por prior art: fragmentos
   Caso A no verificados nunca deben bloquear su propia retransmisión ni
   descartarse por "no verificado todavía" — el TTL/dedup existente ya
   basta como única razón de descarte, igual que hoy.

4. **No adoptar** el patrón de intercambio de claves fuera de banda de
   Briar (QR/en persona) como requisito para Farosos: es incompatible con el
   escenario central de la app (desconocidos bajo escombros, sin
   coordinación previa) y confirma, por contraste, que la visión de "red
   abierta donde extraños deben poder verificarse eventualmente" que motivó
   elegir Ed25519 asimétrico sobre secreto compartido sigue siendo la
   decisión correcta — es la misma lógica que llevó a SSB y a Meshtastic 2.5
   a moverse de secretos compartidos hacia claves asimétricas por nodo.

En síntesis: la arquitectura ya decidida (Ed25519 por dispositivo,
`device_id_hash = hash(pubkey)`, fragmentación pasiva para Caso A, cadena de
hashes con `Secuencia` como índice para Caso B, verificación de Caso B
diferida al gateway) es consistente con el patrón dominante en las cinco
tecnologías revisadas y no requiere cambios. El aporte principal de esta
investigación es evidencia citable de que "verificación diferida a un nodo
con más contexto/conectividad" es un patrón establecido (DTN/BPSec en
particular), no una debilidad del diseño de Farosos.
