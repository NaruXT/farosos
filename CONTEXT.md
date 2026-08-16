# Farosos

Red mesh local de emergencia vía BLE, pensada ante todo para personas atrapadas
bajo escombros después de un sismo, sin cobertura de internet, que necesitan
emitir su estado para ser encontradas. Su beacon salta de teléfono en teléfono
hasta que alguno recupera señal propia o se conecta con otro dispositivo que ya
la tiene, y desde ahí llega a un panel de rescate. La visión de largo plazo es
que comunidades enteras — no solo instituciones — tengan la app instalada
*antes* de que ocurra el sismo, para que la malla ya exista cuando haga falta.

## Language

**Malla**:
El conjunto de teléfonos cercanos que retransmiten beacons entre sí por BLE, sin infraestructura central.
_Avoid_: Red (a secas, es ambiguo con "rol de red")

**Beacon**:
Paquete periódico de 26 bytes que un teléfono emite y retransmite, llevando el estado de una persona (Máquina A: `PersonStateMachine`) más ubicación.
_Avoid_: Mensaje, paquete de estado

**Gateway**:
Un teléfono en el estado `GATEWAY_ACTIVO` de la Máquina B (`NetworkRoleMachine`): detectó conectividad real hacia internet/celular y se anuncia a la malla como puente potencial hacia afuera.
_Avoid_: Nodo de salida, puente (solo como descripción informal, no como término)

**Piloto cerrado**:
El primer grupo real de prueba de Farosos — un puñado de personas conocidas del desarrollador (no una publicación pública en tienda de apps), con instalación manual. Es el hito que valida la malla y el rol de gateway con gente real antes de perseguir adopción masiva.
_Avoid_: Beta, lanzamiento, demo

**Backend de agregación**:
El servicio en la nube al que un gateway sube datos reales de la malla una vez que cruza hacia la red celular/internet normal. Persiste historial y alimenta el panel de rescate. No es parte de la malla BLE — es lo que hay del otro lado del puente que un gateway ofrece.
_Avoid_: Servidor, nube, backend (a secas)

**Panel de rescate**:
Interfaz externa, de acceso controlado (no pública), donde equipo de rescate, gobierno u otro interesado ve en tiempo real e historial el estado agregado que salió de la malla a través de uno o más gateways.
_Avoid_: Dashboard (a secas), panel de administración
