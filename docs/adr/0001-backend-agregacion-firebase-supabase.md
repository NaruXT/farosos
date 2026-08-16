# Backend de agregación: Firebase (Firestore + Auth + Hosting)

El panel de rescate necesita un backend con persistencia de historial y tiempo real nativo. Portal (`useportal.co`) ya se había identificado como candidato de vendor en Fase 2 — es infraestructura de pub/sub en tiempo real con presencia, y sí tiene capacidad de historial, pero esa capacidad no se evaluó a fondo en la sesión donde se tomó esta decisión inicial. Entre Firebase y Supabase (ambos con SDKs nativos maduros para Swift/Kotlin), se eligió **Firebase** (Firestore para datos + Firebase Auth para el acceso controlado del panel + Firebase Hosting para servirlo) porque es un solo vendor que cubre las tres necesidades del piloto, reduciendo el número de cuentas/consolas a coordinar dentro de las 3 semanas disponibles, y porque su SDK móvil sigue siendo el más maduro específicamente para tiempo real + persistencia offline en apps nativas — relevante porque un teléfono gateway tiene conectividad intermitente por definición. Ninguna opción tiene costo esperado a la escala del piloto (5-15 dispositivos).

## Considered Options

- **Portal**: ya evaluado como candidato en Fase 2; descartado esta vez no por una limitación confirmada, sino porque no había tiempo para verificar a fondo si su modelo de historial cubre lo que necesita el panel de rescate.
- **Supabase**: SDK nativo Swift confirmado production-ready (5 años de desarrollo, mantenimiento activo). Su modelo Postgres + Row Level Security es elegante para el requisito de acceso controlado, y su naturaleza relacional encaja bien con "historial". Descartado por esta vez porque no incluye hosting propio para el panel (habría que coordinar un tercer proveedor), y porque el SDK móvil de Firebase tiene más trayectoria específicamente en tiempo real + offline para apps nativas.
- **Firebase (elegido)**: Firestore + Auth + Hosting como un solo vendor, cobertura completa de lo que necesita el piloto sin sumar proveedores nuevos.

## Consequences

Ni Portal ni Supabase quedan descartados permanentemente — si el caso de uso crece más allá del piloto (más escala, necesidad de queries relacionales complejas, o RLS de Supabase resulta más adecuado para control de acceso más fino), vale la pena revisitar esta decisión.
