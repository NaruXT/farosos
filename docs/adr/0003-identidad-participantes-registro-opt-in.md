# Identidad de participantes: registro opt-in al instalar + Firebase Anonymous Auth

El `device_id_hash` es pseudónimo por diseño (`spec/packet-format.md` decisión 6) — nadie puede reidentificarlo externamente, ni siquiera con cooperación de una operadora telefónica (no se deriva de IMEI, número ni ningún identificador que una operadora reconozca). Pero el panel de rescate necesita saber "quién es quién" para ser útil a un equipo de rescate.

Se decidió resolverlo con una colección `participants` (`device_id_hash → name, contacto opcional`) poblada por un flujo de registro **opt-in**, mostrado la primera vez que se abre la app — antes de dejar usar el resto de la app, pero **sin bloquear su uso** si no hay conectividad: los datos se persisten localmente primero (mismo patrón que `deviceIdHash`, Keychain/EncryptedSharedPreferences) y se suben a Firestore recién cuando `ConnectivityMonitor` detecta conectividad — desacoplado por completo de `GATEWAY_ACTIVO`, porque el registro ocurre antes de cualquier emergencia, no durante ella. Las escrituras de la app (registro + subidas futuras del rol de gateway) usan Firebase Anonymous Auth: una sesión anónima por instalación. El panel de rescate usa una credencial compartida separada (email/password) solo para lectura. Cualquier sesión autenticada (anónima o password) puede escribir — sin permisos más finos, una simplificación deliberada proporcional a un piloto de 5-15 personas conocidas.

## Considered Options

- **Registro manual por el desarrollador** (la opción original de esta ADR): más rápido de construir, pero no escala más allá del propio desarrollador operando el piloto, y no es el flujo real que tendría el producto fuera de un piloto.
- **Identidad vía operadora telefónica**: descartada — requiere acceso a identificadores de telecom que iOS/Android ya bloquean a apps de terceros, un acuerdo legal/institucional con la operadora muy por fuera del alcance de cualquier fase cercana del proyecto, y rompe la propiedad de pseudonimia que protege hoy a cualquiera que use la malla sin haberse registrado explícitamente. No resuelve nada que el registro opt-in no resuelva ya para el caso de uso real.
- **Registro opt-in al instalar, no bloqueante (elegido)**: consistente con el principio offline-first del resto del proyecto, y es el flujo real que tendría el producto más allá del piloto.

## Consequences

Un participante que se salta la conectividad justo en el momento del registro sigue funcionando en la malla con un `device_id_hash` sin nombre asociado, hasta que su teléfono recupere señal y suba el perfil pendiente — el panel de rescate debe tolerar mostrar hashes sin nombre para esos casos, no asumir que todo `device_id_hash` tiene un `participants` correspondiente.
