# LAS TULAS — Punto de venta (POS)

Sistema de caja para juguería/cafetería (Perú). Cubre: **caja**, **ventas con vista previa interna de comprobante**, **inventario** y **control** (anulaciones y reportes). No tiene integración de emisión electrónica con SUNAT.

- **Stack:** Java 21 + Spring Boot 4.1.1 + Spring Security (JWT) + Spring Data JPA + PostgreSQL
- **Frontend:** HTML/CSS/JS estáticos servidos por el backend (sin Node, sin build)
- **Roles:** `ADMIN` (gestión) y `CAJERO` (caja)
- **Moneda:** Soles (S/), IGV configurable (18% por defecto)

---

## Requisitos

| Componente | Versión |
|------------|---------|
| JDK | 21+ |
| Maven | 3.9+ (incluye `mvnw`/`mvnw.cmd`) |
| Base de datos | Supabase (PostgreSQL) |
| Navegador | Moderno (Chrome/Edge/Firefox) |

**Puertos:** 8080 (app), 5432 (PostgreSQL)

---

## Puesta en marcha rápida

### 1. Base de datos

El backend apunta de forma predeterminada al **Session Pooler** del proyecto
ProyectoMarcos en Supabase. El certificado público incluido permite validar la
identidad del servidor con TLS. Para otro proyecto, configura la URL JDBC y el
usuario del Session Pooler de ese proyecto mediante `SPRING_DATASOURCE_URL` y
`SPRING_DATASOURCE_USERNAME`.

### 2. Ejecutar migraciones en una base nueva (orden obligatorio)

La base de Supabase ya instalada se usa con su esquema actual. Para una base
nueva, ejecuta desde `psql` las migraciones pendientes sobre la base `postgres`
del proyecto Supabase.

```sql
\i db/migracion_00_esquema_base.sql
\i db/migracion_01_cimientos.sql
\i db/migracion_02_toppings_correlativo.sql
\i db/migracion_03_caja_auditoria.sql
\i db/migracion_04_login_seguridad.sql    # NUEVA: intentos fallidos + bloqueo
\i db/migracion_05_idempotencia_ventas.sql # Clave unica para reintentos de cobro
\i db/migracion_06_sabores_adicionales_categorias.sql
\i db/migracion_07_frutas_cupos_incluidos.sql
\i db/migracion_08_desglose_cliente_nota_venta.sql
\i db/datos_catalogo_inicial.sql           # Opcional: datos de ejemplo
```
En una base ya instalada, ejecuta la migración 05 antes de arrancar esta
versión. Para el desglose de extras cobrados y la nota de venta, aplica la 08
después de la 07. El desglose se conserva desde las nuevas ventas; las anteriores
mantienen sus importes históricos sin reconstruir precios de la carta actual.
Ejecuta las migraciones con una cuenta autorizada para alterar el
esquema; configura en la aplicación una cuenta de runtime con permisos mínimos.

### 3. Variables de entorno
```powershell
# Para desarrollo. En prod usa un gestor de secretos y conserva el mismo valor
# entre reinicios; no generes uno nuevo cada vez que inicies la aplicación.
$env:APP_JWT_SECRET = [Convert]::ToBase64String((1..48 | ForEach-Object { Get-Random -Maximum 256 }))

# Decolecta para autocompletar DNI/RUC (opcional): solicita la API key sin mostrarla.
$secureToken = Read-Host "API key de Decolecta" -AsSecureString
$pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secureToken)
try { $env:DECOLECTA_TOKEN = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer); $secureToken.Dispose() }

# Contraseña de PostgreSQL del proyecto Supabase.
$env:SPRING_DATASOURCE_PASSWORD = "tu-contraseña-de-supabase"

# Perfil producción (desactiva SQL logs, valida esquema, logging WARN)
$env:SPRING_PROFILES_ACTIVE = "prod"
```

Define `SPRING_DATASOURCE_PASSWORD` y, para el perfil `prod`,
`APP_JWT_SECRET`. La clave JWT debe ser estable entre reinicios y tener al menos
32 caracteres. En tu equipo puedes guardar ambos valores en un archivo privado
`.env` junto a `pom.xml`, excluido de Git. En Render, usa las variables privadas
de **Environment**.

### 4. Compilar y ejecutar

En macOS o Linux, inicia la aplicación con `./mvnw spring-boot:run`.

En Windows, usa estos comandos en PowerShell.

```powershell
# Desarrollo
.\mvnw.cmd spring-boot:run

# Tests
.\mvnw.cmd test

# Producción: JAR ejecutable
.\mvnw.cmd clean package
java -jar target\ica-0.0.1-SNAPSHOT.jar
```

La API key de Decolecta se lee desde `DECOLECTA_TOKEN`; no la escribas en
`application.properties`, en el JavaScript ni en el comando `curl` del
navegador. El backend agrega esa clave en `Authorization: Bearer ...` y consulta
el endpoint de DNI/RUC correspondiente. La asignacion `$env:DECOLECTA_TOKEN`
y el comando que inicia el backend deben ejecutarse en el mismo terminal.
Reinicia el backend despues de definirla para que Spring la cargue.

Si inicias desde VS Code u otro IDE, también puedes guardar la clave localmente
en `.env` junto a `pom.xml`, con esta línea: `decolecta.token=TU_API_KEY`.
Ese archivo está ignorado por Git y Spring lo carga al iniciar. Úsalo solo en
desarrollo local; en producción configura `DECOLECTA_TOKEN` mediante el gestor
de secretos del entorno.

#### DNI/RUC en Render

El `.env` de tu PC no se sube a GitHub y el Dockerfile no lo copia. Por eso,
una consulta que funciona localmente puede responder 503 al desplegar.
El token debe configurarse también en el servidor:

1. Abre tu servicio existente en Render y entra en **Environment**.
2. Agrega `DECOLECTA_TOKEN` con el token de tu cuenta de Decolecta, sin comillas
   ni el prefijo `Bearer`. No lo escribas en GitHub ni en el frontend.
3. Guarda los cambios y vuelve a desplegar el servicio para que Spring lo cargue.
4. Inicia sesión en el POS desplegado y prueba una boleta con DNI y una factura
   con RUC. Si el proveedor rechaza el token, revisa su vigencia y los permisos
   de consulta de tu cuenta; el POS mantendrá la sesión y permitirá escribir el nombre.

El `render.yaml` incluido declara el despliegue Docker y las variables necesarias
sin crear ni modificar bases de datos. Si creas un servicio mediante Blueprint,
Render solicita los secretos al crearlo. Para un servicio que ya existe, agrega
`DECOLECTA_TOKEN` manualmente: Render no solicita las nuevas variables `sync: false`
al actualizar un Blueprint. Conserva las credenciales y la URL JDBC de tu base
actual, así como el mismo `APP_JWT_SECRET`.
Referencia: [variables y secretos de Render](https://render.com/docs/configure-environment-variables)
y [variables en Blueprints](https://render.com/docs/blueprint-spec#setting-environment-variables).

La consulta usa `/api/documento` en el mismo dominio del POS y el backend consulta
Decolecta mediante HTTPS. No requiere habilitar CORS en el navegador ni publicar
el token. Si falta la configuración, el log del servidor indica qué variable falta
sin imprimir el token ni los documentos consultados.

También se admite **Environment → Secret Files → Add Secret File**, con nombre
`.env` y contenido `decolecta.token=TU_API_KEY`. La aplicación carga ese archivo
desde `/etc/secrets/.env` al iniciar el contenedor; usa este método o la variable
`DECOLECTA_TOKEN`. Configúralo en el servicio web `lastulas`, no en la base de datos.
En un servicio existente, agregar `render.yaml` al repositorio no completa esos
valores automáticamente. El mensaje “La consulta DNI/RUC no está configurada”
significa que ninguno de los dos métodos suministró una clave al servidor.
Referencia: [archivos secretos de Docker en Render](https://render.com/docs/docker-secrets#accessing-secret-files-at-runtime).

### 5. Acceder
Abrir **http://localhost:8080/**

**Inicialización local solamente:** usuario `admin`, PIN `1234` (sembrado por
`migracion_00_esquema_base.sql`). Es un dato público de desarrollo: no uses
ese usuario/PIN en producción. Para iniciar sesión selecciona el rol e ingresa
solo el PIN. Cambia el PIN inicial
desde *Cierre de Caja → Cambiar mi PIN*
antes de registrar datos reales. Cada trabajador debe tener su propia cuenta.
Los PIN deben ser distintos entre las cuentas del mismo rol.

---

## Pantallas principales

| URL | Pantalla | Rol |
|-----|----------|-----|
| `/index.html` | Login (teclado PIN) | Público |
| `/cajero.html` | POS: carta, carrito, toppings | ADMIN, CAJERO |
| `/cajero_pago.html` | Cobro, vista previa imprimible no oficial, vuelto | ADMIN, CAJERO |
| `/inventario.html` | CRUD productos | ADMIN |
| `/anulacion_boletas.html` | Anular / corregir método pago | ADMIN |
| `/reporte_boletas.html` | Reporte por caja/cajero | ADMIN |
| `/reporte_general.html` | KPIs + gráfico 7 días | ADMIN |
| `/cierre_caja.html` | Apertura/turno/arqueo | ADMIN, CAJERO |
| `/auditoria.html` | Bitácora cambios (solo lectura) | ADMIN |
| `/creacion_usuario.html` | Alta/baja cajeros | ADMIN |

## Comprobante de venta: alcance actual y evolución segura

Al confirmar un cobro exitoso, el POS muestra una vista previa imprimible de 80 mm con el logo, el correlativo interno, fecha, cajero, cliente, productos, subtotal, IGV, total y pago registrado. La pantalla y la impresión dicen **“NO ES UN COMPROBANTE ELECTRÓNICO OFICIAL”**. Es una maqueta operativa; el correlativo `B001-XXXXXX` que se muestra no significa que SUNAT haya recibido, validado o autorizado la venta. No se inventan RUC, domicilio fiscal, hash, QR, número de operación de tarjeta ni datos de aprobación.

La vista previa se construye con la respuesta del servidor después de guardar la venta. El servidor sigue siendo responsable de validar los productos y el pago, calcular importes, asignar el correlativo interno y descontar stock. Si el cobro falla, no se abre el ticket ni se limpia el carrito. Los importes de efectivo y digitales se etiquetan como importes aplicados a la venta, no como dinero originalmente entregado por el cliente.

### Método recomendado para habilitar emisión electrónica real

1. **Completar y validar la identidad tributaria del emisor:** RUC, razón social, domicilio fiscal, establecimiento y series autorizadas; no poner valores de ejemplo en producción.
2. **Elegir el canal de emisión:** integración directa con SUNAT o un proveedor/PSE/OSE autorizado. Definir primero qué documentos y flujos soportará (boleta, factura, notas de crédito y contingencia).
3. **Separar venta de emisión fiscal:** guardar la venta y su detalle como ahora; gestionar la emisión con un módulo/servicio aparte, estados explícitos (`PENDIENTE`, `ACEPTADO`, `RECHAZADO`, `EN_CONTINGENCIA`) y la respuesta original del proveedor. Una caída de SUNAT no debe repetir el cobro ni el descuento de stock.
4. **Hacer reintentos idempotentes:** relacionar cada intento con el mismo `idVenta` y clave estable; reintentar la emisión sin crear otra venta o consumir otro correlativo fiscal. Reservar y controlar la serie fiscal en el lugar definido por SUNAT/proveedor.
5. **Emitir solo con evidencia del proveedor:** generar y firmar el formato requerido, enviar, guardar respuesta/código de aceptación y recién entonces mostrar estado de comprobante aceptado, QR o hash real. Nunca generarlos en el navegador ni inferir aceptación por una respuesta HTTP genérica.
6. **Dar una ruta de consulta/reimpresión:** recuperar el documento y estado persistidos desde una ruta autenticada. La vista previa actual vive en la respuesta inmediata del cobro; no es todavía un archivo fiscal reimprimible.
7. **Validar antes de producción:** pruebas automatizadas con respuestas aceptadas/rechazadas y reintentos, ambiente de pruebas del proveedor/SUNAT, conciliación de correlativos, credenciales protegidas y revisión contable/legal.

## Reportes y controles: diferencias y recomendación

**Reporte General**, **Cierre de Caja** y **Auditoría** pueden mencionar ventas o cajas, pero cumplen funciones distintas. No deben interpretarse como tres totales equivalentes:

| Apartado | Pregunta que responde | Alcance y datos principales | Para quién / uso |
|----------|------------------------|-----------------------------|------------------|
| **Reporte General** (`/reporte_general.html`) | ¿Cómo fueron las ventas del negocio en el período? | Vista agregada del negocio: ventas emitidas, cantidad de boletas, ticket promedio, anulaciones, distribución por método de pago, gráfico diario y detalle paginado por cajero/caja. El rango de fechas filtra los indicadores, el desglose por método y la tabla. | ADMIN; análisis comercial y seguimiento de tendencias. |
| **Cierre de Caja** (`/cierre_caja.html`) | ¿Cuánto efectivo debería haber en la gaveta de este turno y cuánto se contó? | Control operativo de un turno individual: monto inicial, ventas y boletas asociadas al turno, efectivo, pagos digitales, efectivo esperado, efectivo contado y diferencia. Al cerrar, los totales del turno quedan guardados como una foto del arqueo; la pantalla también ofrece historial. ADMIN ve todos los turnos del historial y CAJERO solo los propios. | ADMIN y CAJERO; abrir, cuadrar y cerrar el turno de caja. |
| **Auditoría** (`/auditoria.html`) | ¿Quién hizo qué cambio, a qué registro y cuándo? | Bitácora de acciones, con usuario, fecha, tabla, identificador del registro, acción y valores anterior/nuevo cuando están disponibles. Se consulta en modo de solo lectura y se puede filtrar por tabla, acción y fechas. | ADMIN; revisión y seguimiento de cambios operativos. |

### Por qué conservarlos

- **Conservar Reporte General** si se necesita evaluar el negocio más allá de una caja o un turno: comparar períodos, entender qué métodos de pago se usan y revisar las boletas emitidas. No reemplaza el arqueo porque sus cifras son agregados de ventas, no un conteo físico de efectivo.
- **Conservar Cierre de Caja** si se recibe efectivo en una gaveta. Es el único de estos tres apartados que compara el efectivo contado con el esperado (`monto inicial + efectivo de ventas emitidas del turno`) y registra la diferencia y las observaciones. El monto de ventas de un día no permite detectar por sí solo faltantes o sobrantes en una caja.
- **Conservar Auditoría** si se necesita responsabilidad y trazabilidad. Ayuda a investigar anulaciones, cambios y aperturas/cierres indicando quién actuó y qué valores cambiaron; no es un resumen financiero ni sirve para cuadrar el efectivo.

**Recomendación:** mantener los tres, con sus propósitos y permisos separados. Si se quiere simplificar la navegación, se pueden agrupar visualmente bajo “Reportes y control”, pero no conviene fusionar sus datos ni eliminar uno para usar otro en su lugar. Se perdería, respectivamente, análisis comercial, conciliación física del turno o historial de acciones.

### Por qué pueden diferir sus cifras

1. **El período no es necesariamente el mismo que un turno.** Reporte General agrupa ventas por fechas y Cierre de Caja agrupa ventas asociadas a un turno concreto. Un turno puede cruzar medianoche; también puede haber ventas sin turno abierto, que no entran en ningún arqueo.
2. **El estado de las ventas importa.** El arqueo calcula sus totales con ventas emitidas. Reporte General muestra indicadores separados para ventas emitidas y anuladas; no hay que sumar una anulación como si fuera ingreso vigente.
3. **El efectivo no es el total vendido.** El efectivo esperado incluye el monto inicial y el componente en efectivo de las ventas del turno; los pagos digitales no son dinero que deba estar en la gaveta. El conteo físico se compara con ese efectivo esperado.
4. **El cierre es una foto histórica.** Al cerrar el turno quedan guardados sus totales y diferencia. El reporte general puede cambiar si se anula una venta; eso no reescribe el arqueo que se hizo ese día.
5. **Auditoría cuenta acciones, no ingresos.** Una fila de “ANULAR” o “CERRAR_CAJA” describe un evento de control, no una venta adicional.

**Nota del gráfico:** el gráfico de Reporte General agrupa las ventas emitidas por día para los últimos siete días calendario, incluido hoy. Mantiene ese rango aunque se consulte otro período en los indicadores; una respuesta fallida de la serie se muestra como error y no como una gráfica en cero.

---

## Auditoría técnica y mejoras recomendadas

Esta revisión está enfocada en el uso del POS por un negocio pequeño. La suite automatizada cubre lógica de ventas, caja, autenticación y recursos estáticos; esto no sustituye pruebas de seguridad, restauración de respaldos ni pruebas con un proveedor de emisión fiscal. La evaluación automática de AppCAT se enfoca principalmente en preparación para Azure y actualización de Java; sus advertencias de migración no significan por sí solas que la aplicación tenga fallos operativos.

Las correcciones de seguridad e idempotencia descritas abajo se implementaron
en esta versión; ejecuta también la migración 05 antes de arrancar una base
existente. El respaldo diario requiere programar el script en Windows y ensayar
la restauración en el equipo de operación. En Linux, programa el comando
`pg_dump` equivalente con cron.

### Prioridad inmediata: antes de usar datos reales o exponer la aplicación

| Prioridad | Hallazgo | Acción recomendada | Criterio de cierre |
|-----------|----------|--------------------|--------------------|
| **Resuelta** | El PIN inicial y las credenciales de base de datos no deben convertirse en valores de producción. | El README limita `admin`/`1234` a la inicialización local; las credenciales de BD se leen del entorno y el perfil `prod` las exige. Cada trabajador tiene una cuenta y un PIN propio; el acceso solicita el rol y el PIN, no el nombre de usuario. Cambia cualquier credencial compartida y usa un usuario de BD con permisos mínimos. | El PIN de ejemplo no se usa con datos reales; cada trabajador tiene acceso propio y los secretos no están en archivos de configuración. |
| **Resuelta** | Errores de producción revelaban mensajes/trazas y el secreto JWT aleatorio al reiniciar invalidaba todas las sesiones. | `prod` ya oculta mensajes, trazas y errores de binding, y falla al iniciar si falta `APP_JWT_SECRET`; usa siempre la misma clave protegida entre reinicios. Al exponer el POS fuera del equipo local, configura HTTPS y restringe el acceso de red. | Un reinicio conserva la validez de sesiones y las respuestas públicas de error no revelan detalles internos. |
| **Resuelta** | El limitador usaba una clave común y los fallos de PIN podían bloquear a cajeros que no eran el objetivo. | El login busca primero la cuenta individual; solo esa cuenta suma fallos y el limitador en memoria usa la IP de conexión (`getRemoteAddr`). No se confía automáticamente en `X-Forwarded-For`; detrás de un proxy, configura la confianza de proxy en la infraestructura antes de usarlo para identificar clientes. | Un error de una cuenta no modifica el contador de otra; cada IP tiene su propio límite de solicitudes. |

### Prioridad alta: continuidad e integridad de la operación

| Mejora | Implementación sugerida | Esfuerzo |
|--------|-------------------------|----------|
| **Implementada** — evitar ventas duplicadas al reintentar un cobro tras un timeout o doble clic. | El POS envía una clave UUID por intento, evita doble envío y conserva el intento pendiente en la pestaña hasta obtener respuesta. PostgreSQL impone unicidad; un reintento idéntico devuelve la venta original y una misma clave con datos diferentes se rechaza. Ejecuta `migracion_05_idempotencia_ventas.sql` antes de arrancar. | — |
| **Script implementado; programación y restauración pendientes de operar** — tener respaldos recuperables, no solo un comando manual. | `db/backup-postgresql.ps1` crea un respaldo PostgreSQL de formato custom, lo verifica con `pg_restore` y retiene 30 días por defecto. Programa su ejecución diaria, copia los archivos fuera del equipo/volumen de la base y ensaya la restauración en una base separada. | Bajo–medio |
| Alinear los turnos con el uso de la caja física. | Si cada cajero tiene su propio cajón, documentar esa regla. Si comparten el mismo cajón, controlar el turno abierto por caja física además de por usuario, para evitar arqueos ambiguos. | Medio, si se comparte caja |

### Antes de usar comprobantes como documentos oficiales

La boleta impresa actualmente es una **vista previa no oficial**. No tiene integración con SUNAT y su correlativo interno no acredita autorización tributaria. Si se requiere emisión electrónica:

1. Validar RUC, razón social, domicilio fiscal, establecimiento y series autorizadas.
2. Elegir integración directa con SUNAT o un proveedor/PSE/OSE autorizado y definir los documentos y casos de contingencia necesarios.
3. Separar el registro de la venta de la emisión fiscal, persistiendo estados como `PENDIENTE`, `ACEPTADO`, `RECHAZADO` y `EN_CONTINGENCIA`, junto con la respuesta del proveedor.
4. Asociar reintentos a la misma venta y a una clave estable para no duplicar cobros, ventas ni correlativos.
5. Mostrar como aceptado solo un comprobante con confirmación verificable del proveedor; ofrecer consulta y reimpresión del documento persistido.
6. Probar rechazos, indisponibilidad, reintentos y conciliación en el entorno de pruebas del proveedor, con revisión contable/legal antes de producción.

### Recomendación de alcance

Para un solo local, mantener la aplicación monolítica, Java 21 y PostgreSQL actuales. Las credenciales, el endurecimiento de producción, el login individual y la idempotencia ya están cubiertos en código. Operar los respaldos y probar su restauración; definir además si cada cajero usa su propio cajón o si se comparte uno antes de ampliar el modelo de turnos. La containerización, Kubernetes o una migración a Azure pueden esperar hasta que exista una necesidad concreta de disponibilidad, acceso remoto o crecimiento.

La revisión fue de código y pruebas disponibles; no fue una prueba de penetración ni una validación legal/contable. Las mejoras de esta sección son recomendaciones y no se consideran implementadas hasta que se realicen y validen.

---

## Estado de mejoras (auditoría)

### ✅ **Implementadas previamente**

| # | Mejora | Implementación |
|---|--------|----------------|
| 1 | Correlativo interno mostrado = guardado | `VentaService` pide correlativo a secuencia y fija `fecha_venta` en INSERT |
| 2 | Filtros anulación (caja/cajero) | `idUsuario` como value estable, backend devuelve `cajaNumero` |
| 3 | Numeración "Caja N" estable | Ordenado por `id_usuario` en `ReporteService.numerosDeCaja()` |
| 4 | Reportes con paginación/agregaciones BD | `ReporteService` con Criteria API, misma `Specification` para lista + totales |
| 5 | Validación toppings en servidor | `ToppingCatalogoService` + 7 tests (receta, categoría topping, sin receta) |
| 6 | Categoría 7 (TOPPING) reconocida | Migración 03: `categorias.es_topping` |
| 7 | Método pago / comprobante como catálogos | Enums `MetodoPago` / `TipoComprobante` + migración normalizó datos |
| 8 | Alta producto validada (ref única, categoría existe) | `ProductoController` devuelve 400 legible |
| 9 | Baja producto = lógica (`activo=false`) | Migración 03 + `DELETE` marca inactivo, `POST /reactivar` |
| 10 | Carrito persiste tras expirar sesión | `localStorage` + migración automática desde `sessionStorage` |
| 11 | Caja/turno + arqueo calculado | Migración 03: `cajas_turno`, `ventas.id_caja_turno`, `CajaTurnoService` |
| 12 | **Límite básico de solicitudes de login** | `RateLimitConfig.TokenBucket` → HTTP 429. Se usa la IP de conexión remota, no `X-Forwarded-For`; detrás de un proxy solo debe habilitarse esa cabecera con una lista explícita de proxies confiables. |
| 13 | **Bloqueo básico tras fallos de login** | `Usuario.intentos_fallidos`, `bloqueado_hasta`, reset en login OK. El acceso identifica primero el usuario y registra fallos solo en esa cuenta. |
| 14 | **SecurityConfig endurecido** | `.anyRequest().denyAll()` (solo rutas explícitas públicas) |
| 15 | **Perfil prod** | `show-sql=false`, `ddl-auto=validate`, logging WARN; oculta mensajes, trazas y errores de binding, requiere credenciales de BD y `APP_JWT_SECRET` persistente. |
| 16 | **Reintentos de cobro idempotentes** | Migración 05 agrega una clave UUID única y una huella del cuerpo; la misma clave/cuenta/solicitud devuelve la venta guardada, y reusarla con otros datos se rechaza. |

### ⏳ **Pendientes (baja prioridad / proceso)**

| # | Mejora | Nota |
|---|--------|------|
| 1 | Tests integración con Testcontainers | Preparados pero requieren Docker (`@Disabled` por defecto) |
| 2 | CI/CD pipeline | GitHub Actions / GitLab CI |
| 3 | Impresión térmica dedicada | La vista previa ya se puede imprimir desde el navegador; falta validar/configurar impresora y formato en el entorno real |
| 4 | Integración real Yape/POS | Hoy mockeado en frontend |
| 5 | Frontend: TypeScript + build + tests | Deuda técnica (JS plano actual) |

---

## Problemas frecuentes

| Síntoma | Solución |
|---------|----------|
| `Port 8080 already in use` | `Stop-Process -Id (Get-NetTCPConnection -LocalPort 8080 -State Listen).OwningProcess -Force` |
| `Connection refused:5432` | Levantar PostgreSQL / revisar `spring.datasource.url` |
| `password authentication failed` | Corregir `SPRING_DATASOURCE_USERNAME/PASSWORD` |
| `relation "ventas" does not exist` | Ejecutar migraciones 00–05 en orden |
| El resumen muestra ventas, pero la tabla de boletas da error 500 | Ejecutar `db/migracion_05_idempotencia_ventas.sql` en la misma base usada por la aplicación y reiniciar el backend. La migración agrega campos que Hibernate necesita al leer cada venta. |
| Login 401 siempre | Verificar que se seleccionó el rol correcto y que el PIN pertenece a una cuenta `Activa` de ese rol |
| "Sesión expirada" a media venta | El token dura 12 h; en prod fija `APP_JWT_SECRET` en un gestor de secretos y conserva el mismo valor en reinicios. El carrito se guarda en localStorage. |
| Error al arrancar por `SPRING_DATASOURCE_PASSWORD` | Define la variable en el entorno del proceso; es obligatoria y no hay contraseña guardada en los properties |
| `Idempotency-Key` inválida al cobrar | Ejecuta `migracion_05_idempotencia_ventas.sql` y actualiza el backend y los archivos estáticos a la misma versión |
| 503 al buscar DNI/RUC | Definir `DECOLECTA_TOKEN` o escribir nombre a mano |
| La consulta DNI/RUC rechaza las credenciales | Revisar que `DECOLECTA_TOKEN` sea válido y reiniciar el backend. El error del proveedor no debe cerrar la sesión del POS. |
| Error de servicio al buscar DNI/RUC (502) | Revisar en la consola del backend el motivo técnico; se registra el tipo de fallo sin incluir el token ni el número consultado. |
| Primera boleta `B001-000002` | Ejecutar `migracion_02_toppings_correlativo.sql` (realinea secuencia) |
| 500 al crear producto | Ref duplicada o categoría inexistente → validación en controller |

---

## Operación diaria (resumen)

| Acción | Cómo |
|--------|------|
| **Abrir caja** | Login → *Mi caja* (`cierre_caja.html`) → monto inicial |
| **Cobrar** | Armar pedido → pago → confirmar → revisar/imprimir vista previa no oficial `B001-XXXXXX` |
| **Cerrar caja** | *Mi caja* → contar efectivo → *Cerrar* → muestra diferencia |
| **Crear cajero** | ADMIN → *Creación usuario* → nombre, login, PIN 4+ dígitos |
| **Dar baja cajero** | ADMIN → *Creación usuario* → eliminar (baja lógica, historial intacto) |
| **Dar baja producto** | ADMIN → *Inventario* → baja lógica (`activo=false`) |
| **Cambiar IGV** | `UPDATE parametros SET valor='0.19' WHERE clave='IGV_TASA';` |
| **Anular boleta** | ADMIN → *Anulación* → seleccionar → *Eliminar* → motivo → nota `BC01-XXXXXX` |
| **Corregir método pago** | ADMIN → *Anulación* → pestaña *Modificar* |
| **Ver ingresos día** | ADMIN → *Reporte boletas* / *Reporte general* |
| **Auditoría** | ADMIN → *Auditoría* (tabla, acción, usuario, antes/después) |
| **Backup** | Programa `db/backup-postgresql.ps1` cada día; verifica la copia y conserva 30 días. Ver detalles y restauración abajo. |
| **Rotar JWT** | Nuevo `APP_JWT_SECRET` + reinicio (invalida tokens abiertos) |

### Respaldos y restauración

El script requiere las herramientas cliente `pg_dump` y `pg_restore`, además
de `PGUSER` (o `SPRING_DATASOURCE_USERNAME`) y credenciales de PostgreSQL. Para
ejecuciones automáticas, configura un archivo `pgpass.conf` protegido para la
cuenta de Windows que ejecutará la tarea y define `PGPASSFILE`/`PGUSER` en su
entorno; evita escribir contraseñas en el script o la línea de comandos.

```powershell
$env:PGUSER = "pos_backup"
$env:PGPASSFILE = "$env:APPDATA\postgresql\pgpass.conf"
.\db\backup-postgresql.ps1 -BackupDirectory "D:\Respaldos\LasTulas" -RetentionDays 30
```

Programa esa misma llamada diariamente en el Programador de tareas de Windows.
Guarda además una copia fuera del disco/volumen del servidor de PostgreSQL. El
script genera un archivo de formato custom, verifica que se pueda listar y
elimina solo los respaldos reconocidos de más de `RetentionDays` en el
directorio configurado.

Para comprobar una copia, crea una base vacía separada y restaura ahí; nunca
pruebes sobre la base de operación:

```powershell
createdb -U pos_backup proyectomarcos_recuperacion
pg_restore --no-owner --dbname=proyectomarcos_recuperacion "D:\Respaldos\LasTulas\proyectomarcos_YYYYMMDD_HHMMSS_mmm.dump"
```

Verifica tablas y registros de ventas, detalles, productos y usuarios antes de
considerar probado el procedimiento. No se debe borrar una copia original si
la restauración de ensayo falla.

---

## Licencia / Autor

Proyecto interno — LAS TULAS (Perú). Sin licencia pública.
