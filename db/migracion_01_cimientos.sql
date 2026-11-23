-- =====================================================================
-- LAS TULAS - Migracion 01: CIMIENTOS (puntos 9, 10, 11 y 12 del roadmap)
-- ---------------------------------------------------------------------
-- Ejecutar en pgAdmin sobre la base  proyectomarcos
-- Escribe este script completo de una vez, en una sola transaccion.
-- Es idempotente: si lo corres dos veces no rompe nada.
-- =====================================================================

BEGIN;

-- ---------------------------------------------------------------------
-- 0) Tasa de IGV configurable (punto 12, "IGV dinamico")
-- ---------------------------------------------------------------------
-- El frontend NO tiene la tasa hardcodeada: la lee de /api/config.
-- Si SUNAT cambia el porcentaje, se cambia UNA fila aqui y el backend
-- recalcula todas las ventas nuevas con la tasa vigente.

CREATE TABLE IF NOT EXISTS parametros (
    clave   VARCHAR(40) PRIMARY KEY,
    valor   VARCHAR(100) NOT NULL,
    descripcion VARCHAR(200)
);

INSERT INTO parametros (clave, valor, descripcion) VALUES
    ('IGV_TASA', '0.18', 'Tasa de IGV vigente. 0.18 = 18%. Peru 2026.')
ON CONFLICT (clave) DO UPDATE SET descripcion = EXCLUDED.descripcion;


-- ---------------------------------------------------------------------
-- 1) Punto 11 - Columna estado en ventas (EMITIDA / ANULADA)
--    Antes la anulacion hacia DELETE fisico y /api/ventas devolvia
--    estado:"Emitida" hardcodeado. Ahora el estado es un dato real.
-- ---------------------------------------------------------------------

-- Si la columna no existe todavia (base instalada desde cero), se crea ya con
-- el valor que el enum de Java entiende. En una base que ya la tiene, el IF
-- NOT EXISTS la deja intacta y lo que sigue la normaliza.
ALTER TABLE ventas ADD COLUMN IF NOT EXISTS estado VARCHAR(20) NOT NULL DEFAULT 'EMITIDA';

-- La columna "estado" ya existia antes de esta migracion (la creo el
-- schema original) y guardaba 'Emitida' en mayuscula y minoscula. El enum
-- de Java es EstadoVenta.EMITIDA y Hibernate lee el valor tal cual, asi que
-- con 'Emitida' guardado cualquier consulta a /api/ventas revienta con
-- "No enum constant com.lastulas.ica.EstadoVenta.Emitida".
--
-- Por eso primero se normaliza a mayusculas y recien despues se crea el
-- constraint. Si se invirtiera el orden, el CHECK rechazaria los datos
-- existentes y el script abortaria a mitad de camino.
UPDATE ventas
SET estado = UPPER(TRIM(estado))
WHERE estado IS NOT NULL
  AND estado <> UPPER(TRIM(estado));

-- Cualquier variante historica cae a EMITIDA. Solo quedan EMITIDA y ANULADA.
UPDATE ventas
SET estado = 'EMITIDA'
WHERE UPPER(TRIM(COALESCE(estado, ''))) NOT IN ('EMITIDA', 'ANULADA');

-- El default tambien se corrige: la columna arrastraba 'Emitida' como default
-- de cuando la creo el schema original, y una venta nueva se insertaria con
-- un valor que el enum de Java no reconoce.
ALTER TABLE ventas ALTER COLUMN estado SET DEFAULT 'EMITIDA';

ALTER TABLE ventas DROP CONSTRAINT IF EXISTS ck_ventas_estado;
ALTER TABLE ventas ADD  CONSTRAINT ck_ventas_estado CHECK (estado IN ('EMITIDA', 'ANULADA'));

-- Trazabilidad de la anulacion (quien, cuando, por que).
ALTER TABLE ventas ADD COLUMN IF NOT EXISTS fecha_anulacion     TIMESTAMP;
ALTER TABLE ventas ADD COLUMN IF NOT EXISTS motivo_anulacion    VARCHAR(200);
ALTER TABLE ventas ADD COLUMN IF NOT EXISTS id_usuario_anulacion INTEGER;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_ventas_usuario_anulacion'
    ) THEN
        ALTER TABLE ventas ADD CONSTRAINT fk_ventas_usuario_anulacion
            FOREIGN KEY (id_usuario_anulacion) REFERENCES usuarios(id_usuario);
    END IF;
END $$;


-- ---------------------------------------------------------------------
-- 2) Punto 12 - Desglose fiscal en ventas: subtotal / igv
--
--    CONVENCION CLAVE (leer antes de tocar precios):
--    El precio de productos.precio es el PRECIO FINAL QUE PAGA EL
--    CLIENTE, es decir INCLUYE IGV. Por eso:
--
--        total    = suma de precios de carta x cantidad   (lo que ya se cobraba)
--        subtotal = total / (1 + tasa)                    base imponible
--        igv      = total - subtotal                      impuesto
--
--    Asi el total NO cambia y el precio del menu no se mueve. Solo se
--    desarma el total en sus dos componentes para el comprobante.
--    Si en el futuro se guardan precios SIN IGV, hay que invertir esta
--    formula: subtotal = total, igv = total * tasa.
-- ---------------------------------------------------------------------

ALTER TABLE ventas ADD COLUMN IF NOT EXISTS subtotal NUMERIC(12,2);
ALTER TABLE ventas ADD COLUMN IF NOT EXISTS igv      NUMERIC(12,2);
ALTER TABLE ventas ADD COLUMN IF NOT EXISTS vuelto  NUMERIC(12,2) NOT NULL DEFAULT 0;

-- Backfill de las ventas historicas: se desarma el total ya cobrado
-- usando la tasa vigente. El total NO se toca.
--
-- La tasa se LEE DE LA TABLA parametros, no se escribe 0.18 a mano. Si el dia
-- de manana el IGV baja a 0.05, este scriptDesarma las ventas viejas con la
-- tasa que realmente se les aplico en su dia (que es la que exista cuando se
-- corra el script), y el backend usa la misma para las nuevas. Un 1.18
-- escrito aqui se convertiria en una segunda fuente de verdad que nadie
-- recuerda actualizar.
--
-- Si IGV_TASA no esta (o esta roto), cae a 0.18 en vez de abortar el script:
-- es preferible un desglose estimado a dejar la migracion a medias.
DO $$
DECLARE
    tasa_igv NUMERIC := 0.18;
BEGIN
    BEGIN
        SELECT valor::NUMERIC INTO tasa_igv
        FROM parametros
        WHERE clave = 'IGV_TASA';

        IF tasa_igv IS NULL OR tasa_igv < 0 THEN
            RAISE NOTICE 'IGV_TASA no encontrada o invalida, se usa 0.18 para el backfill.';
            tasa_igv := 0.18;
        END IF;
    EXCEPTION WHEN OTHERS THEN
        RAISE NOTICE 'No se pudo leer IGV_TASA (%), se usa 0.18 para el backfill.', SQLERRM;
        tasa_igv := 0.18;
    END;

    RAISE NOTICE 'Backfill de ventas con tasa IGV %', tasa_igv;

    UPDATE ventas
    SET subtotal = ROUND(total / (1 + tasa_igv), 2),
        igv      = ROUND(total - (total / (1 + tasa_igv)), 2)
    WHERE subtotal IS NULL OR igv IS NULL;
END $$;

-- Una venta emitida siempre tiene el desglose; la anulada lo conserva.
ALTER TABLE ventas ALTER COLUMN subtotal SET NOT NULL;
ALTER TABLE ventas ALTER COLUMN igv      SET NOT NULL;


-- ---------------------------------------------------------------------
-- 3) Punto 11 - Nota de credito en vez de DELETE
--    Anular ya no borra la fila: la marca ANULADA y deja constancia
--    en notas_credito. Es el respaldo que pide SUNAT.
-- ---------------------------------------------------------------------

-- Los toppings se guardaban SOLO como texto ("Chocolate, Coco") dentro de
-- toppings_detalle. Con solo el texto no hay forma de saber que productos
-- se descontaron al vender, y por lo tanto tampoco de devolver su stock al
-- anular. Este columna guarda los ids para que la anulacion sea exacta.
ALTER TABLE detalle_ventas ADD COLUMN IF NOT EXISTS toppings_ids VARCHAR(300);

CREATE TABLE IF NOT EXISTS notas_credito (
    id_nota_credito      SERIAL PRIMARY KEY,
    id_venta             INTEGER NOT NULL REFERENCES ventas(id_venta),
    numero_nota_credito  VARCHAR(30) NOT NULL,
    tipo_comprobante     VARCHAR(30) NOT NULL DEFAULT 'Nota de credito',
    motivo               VARCHAR(200) NOT NULL,
    total_anulado        NUMERIC(12,2) NOT NULL,
    fecha_anulacion      TIMESTAMP NOT NULL DEFAULT NOW(),
    id_usuario_anulacion INTEGER REFERENCES usuarios(id_usuario)
);

-- Numeracion propia de la nota de credito, independiente de la boleta.
CREATE SEQUENCE IF NOT EXISTS seq_nota_credito START 1;

-- Una venta solo se puede anular una vez. El indice unico es la ultima
-- linea de defensa: aunque dos cajeros pulsen a la vez, solo uno gana.
CREATE UNIQUE INDEX IF NOT EXISTS ux_notas_credito_venta ON notas_credito (id_venta);
CREATE UNIQUE INDEX IF NOT EXISTS ux_notas_credito_numero ON notas_credito (numero_nota_credito);

-- Indice para los reportes agregados del punto 13 (que se haran en el
-- backend): filtrar por estado + fecha es la consulta mas frecuente.
CREATE INDEX IF NOT EXISTS ix_ventas_estado_fecha ON ventas (estado, fecha_venta DESC);
CREATE INDEX IF NOT EXISTS ix_ventas_usuario_fecha ON ventas (id_usuario, fecha_venta DESC);


-- ---------------------------------------------------------------------
-- 4) Punto 9 - PIN con hash
--    El PIN estaba en texto plano (codigo_pin) y se comparaba con "=".
--    Se agrega pin_hash para BCrypt. Los usuarios viejo siguen entrando:
--    si pin_hash esta vacio se valida el PIN plano y se hashea al vuelo
--    en su primer login exitoso (transparente, sin obligar a reinscribir).
-- ---------------------------------------------------------------------

ALTER TABLE usuarios ADD COLUMN IF NOT EXISTS pin_hash VARCHAR(100);

-- Convierte los PIN de 4 digitos a texto, porque al comparaarlos con
-- BCrypt un "0123" numerico no matchearia contra un hash de "123".
-- (Solo afecta a los usuarios legacy; los nuevos ya guardan texto.)
UPDATE usuarios
SET codigo_pin = LPAD(codigo_pin, 4, '0')
WHERE codigo_pin ~ '^[0-9]+$' AND LENGTH(codigo_pin) < 4;


-- ---------------------------------------------------------------------
-- 5) Sincroniza productos.estado con el stock real
--
--    "estado" es un dato DERIVADO del stock, no un campo que se elija a
--    mano. El problema: hasta ahora solo se actualizaba cuando el admin
--    editaba el producto desde el inventario. Si una venta dejaba el stock
--    en cero, el producto seguia announcing "Disponible" en el panel.
--
--    Desde ahora el UPDATE atomico de stock recalcula el estado en la misma
--    sentencia (ver ProductoRepository.descontarStock), asi que esto solo
--    sirve para poner al dia lo que quedo desfasado antes del cambio.
--    Los mismos umbrales estan en ProductoEstado.java.
-- ---------------------------------------------------------------------

UPDATE productos
SET estado = CASE
        WHEN COALESCE(stock, 0) <= 0  THEN 'Agotado'
        WHEN COALESCE(stock, 0) <= 10 THEN 'Stock bajo'
        ELSE 'Disponible'
    END
WHERE estado IS DISTINCT FROM CASE
        WHEN COALESCE(stock, 0) <= 0  THEN 'Agotado'
        WHEN COALESCE(stock, 0) <= 10 THEN 'Stock bajo'
        ELSE 'Disponible'
    END;

COMMENT ON COLUMN ventas.subtotal      IS 'Base imponible sin IGV. Se deriva del total cobrado, no se envia desde el navegador.';
COMMENT ON COLUMN ventas.igv           IS 'Impuesto. total - subtotal.';
COMMENT ON COLUMN ventas.estado        IS 'EMITIDA o ANULADA. Una venta anulada NUNCA se borra.';
COMMENT ON COLUMN usuarios.pin_hash    IS 'BCrypt del PIN. Si es NULL el usuario es legacy y se migra en su proximo login.';
COMMENT ON COLUMN productos.estado     IS 'Derivado del stock: 0 = Agotado, 1-10 = Stock bajo, 11+ = Disponible. No se edita a mano.';

COMMIT;

-- =====================================================================
-- VERIFICACION (todas deben devolver 0 filas o 0)
-- =====================================================================
-- SELECT * FROM ventas WHERE subtotal IS NULL OR igv IS NULL;
-- SELECT * FROM ventas WHERE estado NOT IN ('EMITIDA', 'ANULADA');
-- SELECT * FROM productos WHERE estado IS DISTINCT FROM CASE
--     WHEN COALESCE(stock,0) <= 0 THEN 'Agotado'
--     WHEN COALESCE(stock,0) <= 10 THEN 'Stock bajo'
--     ELSE 'Disponible' END;
-- SELECT id_venta, estado, total, subtotal, igv FROM ventas ORDER BY id_venta DESC LIMIT 5;
-- SELECT * FROM parametros;
-- =====================================================================
