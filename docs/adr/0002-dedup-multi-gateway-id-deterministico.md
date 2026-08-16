# Deduplicación multi-gateway: ID de documento determinístico por (persona, secuencia)

Cerca de una zona de desastre puede haber más de un teléfono en `GATEWAY_ACTIVO` simultáneamente, y varios pueden conocer el mismo `DeviceIDHash` con la misma `Secuencia` — cada uno subiendo su propia copia al backend de agregación generaría documentos duplicados para el mismo estado real de una persona.

Se decidió que el ID del documento en Firestore (`mesh_states`) no sea autogenerado, sino determinístico: `{device_id_hash}_{sequence}`. Cada gateway que sube esa combinación escribe (upsert) el mismo documento en vez de crear uno nuevo, usando `arrayUnion` sobre un campo `confirmed_by_gateways` para acumular qué gateways la confirmaron. El historial queda correcto sin trabajo extra: un documento nuevo solo quiere decir un cambio real de estado de una persona (`sequence` nuevo), no un evento de subida de un gateway — y la redundancia de tener varios gateways cerca se convierte en una señal de confianza en vez de ruido a reconciliar del lado del panel.

## Considered Options

- **IDs autogenerados + deduplicar en el panel de lectura**: más simple de escribir, pero empuja el trabajo de reconciliación (¿cuál copia mostrar? ¿cómo evitar parpadeo?) al panel de rescate, y crece el almacenamiento sin necesidad.
- **ID determinístico `{device_id_hash}_{sequence}` (elegido)**: la escritura misma es idempotente, sin reconciliación necesaria en lectura, y el mismo camino de código sirve tanto para el volcado inicial al entrar a `GATEWAY_ACTIVO` como para las actualizaciones incrementales.

## Consequences

`sequence` pasa a ser una pieza estructural del esquema del backend, no solo un campo del protocolo BLE — cualquier cambio futuro a la semántica de `sequence` (p. ej. manejo de wraparound, hoy explícitamente no implementado según `spec/packet-format.md` decisión 10) afecta también la deduplicación del backend, no solo la malla.
