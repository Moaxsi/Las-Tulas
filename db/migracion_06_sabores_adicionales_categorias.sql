-- MIGRACION 06 - Sabores gratuitos y toppings por categorias de la carta.
-- Ejecutar TODO este archivo en Query Tool de pgAdmin, en la misma base
-- usada por la aplicacion, despues de migracion_05_idempotencia_ventas.sql.
-- Luego reiniciar el backend con el codigo actualizado.
-- Es transaccional e idempotente. No borra productos, recetas ni ventas.
-- Los sabores usan el mismo inventario: cada seleccion consume una unidad
-- por unidad del producto vendido y vuelve al stock al anular la venta.

BEGIN;

ALTER TABLE categorias ADD COLUMN IF NOT EXISTS es_sabor BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE categorias DROP CONSTRAINT IF EXISTS ck_categoria_tipo_adicional;
ALTER TABLE categorias ADD CONSTRAINT ck_categoria_tipo_adicional
    CHECK (NOT (es_topping AND es_sabor));

-- El catalogo inicial contiene ids explicitos; alinear antes del nuevo alta.
LOCK TABLE categorias IN SHARE ROW EXCLUSIVE MODE;
SELECT setval(pg_get_serial_sequence('categorias', 'id_categoria'),
              GREATEST(COALESCE(MAX(id_categoria), 0), 1), MAX(id_categoria) IS NOT NULL)
FROM categorias;

INSERT INTO categorias (nombre, descripcion, es_topping, es_sabor)
SELECT 'SABORES', 'Sabores de helado y frutas sin costo adicional', FALSE, TRUE
WHERE NOT EXISTS (SELECT 1 FROM categorias WHERE UPPER(BTRIM(nombre)) = 'SABORES');

UPDATE categorias SET es_sabor = TRUE, es_topping = FALSE
WHERE UPPER(BTRIM(nombre)) = 'SABORES';

-- Cero es un precio valido: un topping puede ser gratuito o de pago.
ALTER TABLE productos DROP CONSTRAINT IF EXISTS productos_precio_check;
ALTER TABLE productos ADD CONSTRAINT productos_precio_check CHECK (precio >= 0);

CREATE TABLE IF NOT EXISTS adicional_categorias (
    id_adicional INTEGER NOT NULL REFERENCES productos(id_producto) ON DELETE CASCADE,
    id_categoria INTEGER NOT NULL REFERENCES categorias(id_categoria) ON DELETE CASCADE,
    PRIMARY KEY (id_adicional, id_categoria)
);
CREATE INDEX IF NOT EXISTS ix_adicional_categorias_categoria
    ON adicional_categorias(id_categoria);

-- Trasladar las recetas anteriores UNA sola vez. Para cada categoria se
-- toma la union de toppings que sus productos ya tenian configurados.
-- Los productos sin receta dejan de aceptar todos los toppings por defecto.
-- Las siguientes ejecuciones respetan lo que el admin edite en inventario.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM parametros WHERE clave = 'MIGRACION_06_CATEGORIAS') THEN
        INSERT INTO adicional_categorias(id_adicional, id_categoria)
        SELECT DISTINCT pt.id_topping, base.id_categoria
        FROM producto_toppings pt
        JOIN productos base ON base.id_producto = pt.id_producto
        JOIN categorias cb ON cb.id_categoria = base.id_categoria
        JOIN productos adicional ON adicional.id_producto = pt.id_topping
        JOIN categorias ca ON ca.id_categoria = adicional.id_categoria
        WHERE ca.es_topping AND NOT cb.es_topping AND NOT cb.es_sabor
        ON CONFLICT DO NOTHING;

        INSERT INTO parametros(clave, valor, descripcion)
        VALUES ('MIGRACION_06_CATEGORIAS', '1', 'Recetas migradas a categorias; no repetir la carga inicial');
    END IF;
END $$;

ALTER TABLE detalle_ventas
    ADD COLUMN IF NOT EXISTS sabores_detalle TEXT,
    ADD COLUMN IF NOT EXISTS sabores_ids TEXT;

-- Regla tambien en PostgreSQL: un sabor nunca puede tener costo.
CREATE OR REPLACE FUNCTION validar_precio_sabor() RETURNS TRIGGER AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM categorias WHERE id_categoria = NEW.id_categoria AND es_sabor)
       AND NEW.precio <> 0 THEN
        RAISE EXCEPTION 'Los sabores deben tener precio cero';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
DROP TRIGGER IF EXISTS trg_validar_precio_sabor ON productos;
CREATE TRIGGER trg_validar_precio_sabor
    BEFORE INSERT OR UPDATE OF precio, id_categoria ON productos
    FOR EACH ROW EXECUTE FUNCTION validar_precio_sabor();

CREATE OR REPLACE FUNCTION validar_categoria_adicional() RETURNS TRIGGER AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM productos p JOIN categorias c USING (id_categoria)
        WHERE p.id_producto = NEW.id_adicional AND (c.es_topping OR c.es_sabor)
    ) THEN
        RAISE EXCEPTION 'El adicional debe ser un topping o un sabor';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM categorias WHERE id_categoria = NEW.id_categoria
        AND NOT es_topping AND NOT es_sabor
    ) THEN
        RAISE EXCEPTION 'El destino debe ser una categoria de productos de la carta';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
DROP TRIGGER IF EXISTS trg_validar_categoria_adicional ON adicional_categorias;
CREATE TRIGGER trg_validar_categoria_adicional
    BEFORE INSERT OR UPDATE ON adicional_categorias
    FOR EACH ROW EXECUTE FUNCTION validar_categoria_adicional();

COMMENT ON TABLE adicional_categorias IS
    'Categorias de carta que admiten cada topping o sabor. Sin filas, no se ofrece en caja.';
COMMENT ON COLUMN categorias.es_sabor IS 'Categoria de sabores gratuitos; no se vende como producto base.';
COMMENT ON COLUMN detalle_ventas.sabores_detalle IS 'Nombres de los sabores al vender; preserva el comprobante historico.';
COMMENT ON COLUMN detalle_ventas.sabores_ids IS 'CSV de ids de sabores para devolver su stock al anular.';

COMMIT;

-- Verificacion (puedes ejecutarla despues):
-- SELECT id_categoria, nombre, es_topping, es_sabor FROM categorias ORDER BY id_categoria;
-- SELECT p.referencia, p.nombre_producto, c.nombre AS categoria_destino
-- FROM adicional_categorias ac JOIN productos p ON p.id_producto = ac.id_adicional
-- JOIN categorias c ON c.id_categoria = ac.id_categoria ORDER BY p.referencia, c.nombre;
-- A continuacion crea tus sabores y ajusta sus categorias desde Inventario.

-- =====================================================================
-- AMPLIACION 06 - Categorias y notas por producto del pedido.
-- Todo el contenido anterior se conserva intacto. Si ya ejecutaste la
-- primera parte de la migracion 06, puedes ejecutar desde este BEGIN.
-- Tambien se puede volver a ejecutar el archivo completo.
-- =====================================================================
BEGIN;

LOCK TABLE categorias, productos, adicional_categorias IN SHARE ROW EXCLUSIVE MODE;

-- Conservar el id original de DUCHITAS y de TOPPIN. Si ya existen los
-- nombres corregidos, fusionarlos antes de renombrar para evitar duplicados.
-- Los productos conservan ids, referencias, stock, recetas e historial.
DO $$
DECLARE
    cambio RECORD;
    conservar INTEGER;
    duplicada RECORD;
BEGIN
    FOR cambio IN SELECT * FROM (VALUES
        ('DUCHITAS', 'DULCHITAS', FALSE),
        ('TOPPIN', 'TOPPING', TRUE)
    ) AS cambios(anterior, nuevo, topping)
    LOOP
        SELECT id_categoria INTO conservar FROM categorias
        WHERE UPPER(BTRIM(nombre)) IN (cambio.anterior, cambio.nuevo)
        ORDER BY (UPPER(BTRIM(nombre)) = cambio.anterior) DESC, id_categoria
        LIMIT 1;

        IF conservar IS NULL THEN CONTINUE; END IF;

        UPDATE categorias SET es_topping = cambio.topping, es_sabor = FALSE
        WHERE id_categoria = conservar;

        FOR duplicada IN SELECT id_categoria FROM categorias
            WHERE UPPER(BTRIM(nombre)) IN (cambio.anterior, cambio.nuevo)
              AND id_categoria <> conservar
        LOOP
            -- Una categoria topping no es un destino valido. En lugar de
            -- borrar una asignacion anomala, detener este bloque y revisarla.
            IF cambio.topping AND EXISTS (
                SELECT 1 FROM adicional_categorias WHERE id_categoria = duplicada.id_categoria
            ) THEN
                RAISE EXCEPTION 'La categoria topping % figura como destino; revisa esas asignaciones antes de fusionar.', duplicada.id_categoria;
            END IF;

            UPDATE productos SET id_categoria = conservar
            WHERE id_categoria = duplicada.id_categoria;

            IF NOT cambio.topping THEN
                INSERT INTO adicional_categorias(id_adicional, id_categoria)
                SELECT id_adicional, conservar FROM adicional_categorias
                WHERE id_categoria = duplicada.id_categoria
                ON CONFLICT DO NOTHING;
            END IF;
            DELETE FROM adicional_categorias WHERE id_categoria = duplicada.id_categoria;
            DELETE FROM categorias WHERE id_categoria = duplicada.id_categoria;
        END LOOP;

        UPDATE categorias SET nombre = cambio.nuevo WHERE id_categoria = conservar;
    END LOOP;
END $$;

SELECT setval(pg_get_serial_sequence('categorias', 'id_categoria'),
              GREATEST(COALESCE(MAX(id_categoria), 0), 1), MAX(id_categoria) IS NOT NULL)
FROM categorias;

INSERT INTO categorias(nombre, descripcion, es_topping, es_sabor)
SELECT nuevas.nombre, nuevas.descripcion, FALSE, FALSE
FROM (VALUES
    ('HELADOS', 'Helados de la carta'),
    ('PROMOS', 'Promociones del negocio'),
    ('OTROS', 'Otros productos de la carta')
) AS nuevas(nombre, descripcion)
WHERE NOT EXISTS (SELECT 1 FROM categorias c WHERE UPPER(BTRIM(c.nombre)) = nuevas.nombre);

ALTER TABLE detalle_ventas ADD COLUMN IF NOT EXISTS notas VARCHAR(300);
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint
                   WHERE conrelid = 'detalle_ventas'::regclass AND conname = 'ck_detalle_ventas_notas') THEN
        ALTER TABLE detalle_ventas ADD CONSTRAINT ck_detalle_ventas_notas
            CHECK (CHAR_LENGTH(notas) <= 300);
    END IF;
END $$;
COMMENT ON COLUMN detalle_ventas.notas IS
    'Indicacion opcional por producto del pedido, hasta 300 caracteres; se conserva y se imprime en la boleta.';

COMMIT;

-- Verificacion de la ampliacion:
-- SELECT id_categoria, nombre, es_topping, es_sabor FROM categorias ORDER BY id_categoria;
-- SELECT id_detalle, nombre_producto, notas FROM detalle_ventas WHERE notas IS NOT NULL;
