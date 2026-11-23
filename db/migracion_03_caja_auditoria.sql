-- =====================================================================
-- MIGRACION 03 - Modulo de caja, auditoria y catalogo canonico
--
-- Es idempotente: se puede correr las veces que haga falta.
--
-- Que resuelve (los puntos 5 a 12 de la seccion 14 del README):
--
-- 1) categorias.es_topping
--    El POS tenia escrita a mano la categoria 5 (TOPPIN) como unica fuente de
--    toppings, pero la carta tambien tiene la 7 (TOPPING). Con la columna, el
--    backend y el frontend preguntan "que categorias son topping" en vez de
--    hardcodear numeros que cambian con la carta.
--
-- 2) productos.activo (baja logica)
--    Borrar un producto fisicamente se llevaba por delante sus relaciones de
--    producto_toppings (ON DELETE CASCADE) y dejaba huecos en el historial.
--    A partir de aqui DELETE marca activo = FALSE y el producto sale de la
--    carta pero sigue existiendo.
--
-- 3) metodos_pago.codigo / tipos_comprobante.codigo
--    El texto guardado era libre: conviven 'Efectivo', 'EFECTIVO' y
--    'EFECTIVO_COMBINADO' en la misma columna, y un reporte por metodo de pago
--    acababa agrupando cadenas distintas que significan lo mismo. Se agrega el
--    codigo canonico y se normalizan las ventas que ya existen.
--
-- 4) cajas_turno + ventas.id_caja_turno
--    Apertura y cierre de turno con arqueo: monto declarado contra monto
--    contado y la diferencia. Cada venta queda pegada al turno en el que se
--    cobro, asi que el arqueo se calcula con SUM sobre datos reales y no con
--    un total que el cajero escribe a mano.
--
-- 5) auditoria
--    Historial de cambios: quien modifico el metodo de pago y cual era el
--    valor anterior, quien dio de baja un producto, quien abrio y cerro caja.
--
-- Corre DESPUES de migracion_02. Si algo ya existia (por ejemplo, una base
-- donde se creo la tabla a mano), el IF NOT EXISTS / DO NOTHING lo respeta.
-- =====================================================================

BEGIN;

-- ---------------------------------------------------------------------
-- 1) Que categorias son toppings
-- ---------------------------------------------------------------------
-- Se marca por NOMBRE, no por id: las categorias 5 y 7 valen en esta carta,
-- pero en otra base pueden tener otros ids. Lo que importa es el nombre.
-- La condicion cubre los dos nombres con los que aparecen en la carta.

ALTER TABLE categorias
    ADD COLUMN IF NOT EXISTS es_topping BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE categorias SET es_topping = TRUE
WHERE UPPER(nombre) IN ('TOPPIN', 'TOPPING', 'TOPPINGS', 'ADICIONALES')
  AND es_topping = FALSE;

CREATE INDEX IF NOT EXISTS ix_categorias_es_topping ON categorias (es_topping);

COMMENT ON COLUMN categorias.es_topping IS
    'TRUE si los productos de esta categoria son toppings, no platos. El POS los oculta de la carta y los ofrece como adicionales (punto 6).';

-- ---------------------------------------------------------------------
-- 2) Baja logica de productos (punto 9)
-- ---------------------------------------------------------------------
-- activo = FALSE es la "baja": el producto desaparece de la carta y del POS,
-- pero la fila sigue existiendo para que el historial de ventas no tenga
-- huecos. Se separa de "estado" (Disponible / Stock bajo / Agotado), que
-- describe el STOCK y no la existencia del producto.

ALTER TABLE productos
    ADD COLUMN IF NOT EXISTS activo BOOLEAN NOT NULL DEFAULT TRUE;

CREATE INDEX IF NOT EXISTS ix_productos_activo ON productos (activo);

COMMENT ON COLUMN productos.activo IS
    'FALSE = producto dado de baja. Se conserva la fila para no romper el historial de detalle_ventas (punto 9).';

-- ---------------------------------------------------------------------
-- 3) Catalogo canonico de metodo de pago y tipo de comprobante (punto 7)
-- ---------------------------------------------------------------------
-- codigo es el valor que viaja y se guarda (ventas.metodo_pago); nombre es la
-- etiqueta que se muestra. El enum del backend es quien decide cual es el
-- codigo valido; esta tabla deja ver que hay configurado.

ALTER TABLE metodos_pago
    ADD COLUMN IF NOT EXISTS codigo VARCHAR(30);

ALTER TABLE tipos_comprobante
    ADD COLUMN IF NOT EXISTS codigo VARCHAR(30);

UPDATE metodos_pago SET codigo = 'EFECTIVO' WHERE UPPER(TRIM(nombre_metodo)) = 'EFECTIVO';
UPDATE metodos_pago SET codigo = 'POS'      WHERE UPPER(TRIM(nombre_metodo)) = 'POS';
UPDATE metodos_pago SET codigo = 'YAPE'     WHERE UPPER(TRIM(nombre_metodo)) = 'YAPE';
UPDATE metodos_pago SET codigo = 'PLIN'     WHERE UPPER(TRIM(nombre_metodo)) = 'PLIN';

UPDATE tipos_comprobante SET codigo = 'BOLETA_SIMPLE' WHERE UPPER(TRIM(nombre_comprobante)) = 'BOLETA SIMPLE';
UPDATE tipos_comprobante SET codigo = 'BOLETA_DNI'    WHERE UPPER(TRIM(nombre_comprobante)) = 'BOLETA CON DNI';
UPDATE tipos_comprobante SET codigo = 'FACTURA'       WHERE UPPER(TRIM(nombre_comprobante)) = 'FACTURA';

-- El combinado no estaba sembrado y el POS lo usa todo el tiempo.
INSERT INTO metodos_pago (nombre_metodo, codigo)
VALUES ('Efectivo combinado', 'EFECTIVO_COMBINADO')
ON CONFLICT (nombre_metodo) DO UPDATE SET codigo = EXCLUDED.codigo;

CREATE UNIQUE INDEX IF NOT EXISTS ux_metodos_pago_codigo
    ON metodos_pago (codigo) WHERE codigo IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS ux_tipos_comprobante_codigo
    ON tipos_comprobante (codigo) WHERE codigo IS NOT NULL;

-- Normalizar lo ya cobrado. Antes una misma forma de pago vivia escrita de
-- varias maneras y cada reporte las contaba como metodos distintos.
UPDATE ventas SET metodo_pago = UPPER(TRIM(metodo_pago))
WHERE metodo_pago IS NOT NULL
  AND UPPER(TRIM(metodo_pago)) IN ('EFECTIVO', 'POS', 'YAPE', 'PLIN', 'EFECTIVO_COMBINADO');

UPDATE ventas SET metodo_pago = 'EFECTIVO_COMBINADO'
WHERE UPPER(TRIM(COALESCE(metodo_pago, ''))) IN
      ('EFECTIVO COMBINADO', 'EFECTIVO + POS', 'EFECTIVO+YAPE', 'COMBINADO');

UPDATE ventas SET tipo_comprobante = 'BOLETA_SIMPLE'
WHERE UPPER(TRIM(COALESCE(tipo_comprobante, ''))) = 'BOLETA SIMPLE';
UPDATE ventas SET tipo_comprobante = 'BOLETA_DNI'
WHERE UPPER(TRIM(COALESCE(tipo_comprobante, ''))) IN ('BOLETA CON DNI', 'BOLETA CON D.N.I');
UPDATE ventas SET tipo_comprobante = 'FACTURA'
WHERE UPPER(TRIM(COALESCE(tipo_comprobante, ''))) = 'FACTURA';

-- Si queda algo que el backend no reconoce, se avisa en vez de dejar un valor
-- a medias que rompa el agrupamiento del reporte.
DO $$
DECLARE
    metodosraros TEXT;
    comprobantesraros TEXT;
BEGIN
    SELECT STRING_AGG(DISTINCT metodo_pago, ', ') INTO metodosraros
    FROM ventas
    WHERE metodo_pago IS NOT NULL
      AND UPPER(TRIM(metodo_pago)) NOT IN
          ('EFECTIVO', 'POS', 'YAPE', 'PLIN', 'EFECTIVO_COMBINADO');

    SELECT STRING_AGG(DISTINCT tipo_comprobante, ', ') INTO comprobantesraros
    FROM ventas
    WHERE tipo_comprobante IS NOT NULL
      AND UPPER(TRIM(tipo_comprobante)) NOT IN
          ('BOLETA_SIMPLE', 'BOLETA_DNI', 'FACTURA');

    IF metodosraros IS NOT NULL THEN
        RAISE WARNING 'metodos_pago que NO son canonicos (el backend los normaliza a EFECTIVO): %',
                      metodosraros;
    END IF;
    IF comprobantesraros IS NOT NULL THEN
        RAISE WARNING 'tipo_comprobante que NO es canonico: %', comprobantesraros;
    END IF;
END $$;

COMMENT ON COLUMN ventas.metodo_pago IS
    'Codigo canonico: EFECTIVO, POS, YAPE, PLIN o EFECTIVO_COMBINADO. Ver enum MetodoPago.';
COMMENT ON COLUMN ventas.tipo_comprobante IS
    'Codigo canonico: BOLETA_SIMPLE, BOLETA_DNI o FACTURA. Ver enum TipoComprobante.';

-- ---------------------------------------------------------------------
-- 4) Cierre y arqueo de caja (punto 11)
-- ---------------------------------------------------------------------
-- No es un checkbox: efectivo_declarado sale de SUM(monto_efectivo) de las
-- ventas del turno, y efectivo_contado es lo que el cajero dice que hay en la
-- gaveta. La diferencia sale de restar, no de que nadie la escriba.

CREATE TABLE IF NOT EXISTS cajas_turno (
    id_caja_turno      SERIAL PRIMARY KEY,
    id_usuario        INTEGER NOT NULL,
    fecha_apertura    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    fecha_cierre      TIMESTAMP,

    -- Con cuanto abre el turno la gaveta.
    monto_inicial     NUMERIC(10,2) NOT NULL DEFAULT 0,

    -- Lo que dice el cajero al cerrar. NULL mientras el turno esta abierto.
    efectivo_contado  NUMERIC(10,2),

    -- Totales del turno. Se llenan al cerrar con SUM sobre las ventas del
    -- turno; mientras esta abierto se leen bajo demanda.
    total_ventas      NUMERIC(12,2) NOT NULL DEFAULT 0,
    total_boletas     INTEGER NOT NULL DEFAULT 0,
    total_efectivo    NUMERIC(12,2) NOT NULL DEFAULT 0,
    total_digital     NUMERIC(12,2) NOT NULL DEFAULT 0,
    diferencia        NUMERIC(10,2),

    observaciones     VARCHAR(300),
    estado            VARCHAR(20) NOT NULL DEFAULT 'ABIERTA',

    CONSTRAINT fk_caja_turno_usuario FOREIGN KEY (id_usuario)
        REFERENCES usuarios (id_usuario)
);

-- Un solo turno abierto por persona: es lo que hace que "abrir caja" sea
-- idempotente de verdad y no dos arqueos competing por el mismo efectivo.
CREATE UNIQUE INDEX IF NOT EXISTS ux_caja_turno_abierta
    ON cajas_turno (id_usuario) WHERE estado = 'ABIERTA';

CREATE INDEX IF NOT EXISTS ix_caja_turno_usuario_fecha
    ON cajas_turno (id_usuario, fecha_apertura DESC);

COMMENT ON TABLE cajas_turno IS
    'Turno de caja con arqueo. Cada venta queda pegada a su turno via ventas.id_caja_turno.';
COMMENT ON COLUMN cajas_turno.diferencia IS
    'efectivo_contado - (monto_inicial + total_efectivo). Positivo = sobrante, negativo = faltante.';

-- Cada venta se cuelga del turno en el que se cobro. Sin FK a proposito:
-- si un turno se borrara, las boletas deben quedarse igual (mismo criterio
-- que id_usuario en ventas).
ALTER TABLE ventas
    ADD COLUMN IF NOT EXISTS id_caja_turno INTEGER;

CREATE INDEX IF NOT EXISTS ix_ventas_caja_turno
    ON ventas (id_caja_turno);

COMMENT ON COLUMN ventas.id_caja_turno IS
    'Turno de caja al que pertenece esta venta (punto 11). NULL si se cobro sin turno abierto.';

-- ---------------------------------------------------------------------
-- 5) Auditoria de cambios (punto 12)
-- ---------------------------------------------------------------------
-- Una fila por cambio, con el valor anterior y el nuevo. Es la respuesta a
-- "quien cambio esto y de que a que": sin valor_anterior no hay auditoria,
-- solo un estado final.

CREATE TABLE IF NOT EXISTS auditoria (
    id_auditoria      BIGSERIAL PRIMARY KEY,
    tabla             VARCHAR(50) NOT NULL,
    id_registro       INTEGER,
    accion            VARCHAR(30) NOT NULL,
    id_usuario        INTEGER,
    usuario_nombre    VARCHAR(120),
    valor_anterior    TEXT,
    valor_nuevo       TEXT,
    detalle           TEXT,
    fecha             TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS ix_auditoria_fecha ON auditoria (fecha DESC);
CREATE INDEX IF NOT EXISTS ix_auditoria_tabla ON auditoria (tabla, id_registro);

COMMENT ON TABLE auditoria IS
    'Historial de cambios administrative. No tiene FK a proposito: el registroauditado puede desaparecer (por ejemplo una baja logica) y la traza se conserva.';

COMMIT;

-- =====================================================================
-- VERIFICACION
--   SELECT * FROM categorias WHERE es_topping;
--   SELECT count(*) FROM productos WHERE NOT activo;
--   SELECT metodo_pago, tipo_comprobante, count(*) FROM ventas GROUP BY 1,2;
--   SELECT * FROM cajas_turno;
-- =====================================================================