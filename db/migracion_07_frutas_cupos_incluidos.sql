-- LAS TULAS | MIGRACION 07 | Frutas y complementos incluidos por producto
-- SUPABASE: copiar TODO este archivo en SQL Editor del proyecto correcto
-- y ejecutar con el rol postgres (o propietario autorizado de estas tablas).
-- REQUISITO: esquema public con las migraciones 00 a 06 completas.
-- No es un instalador para una base vacia.
-- Puede repetirse: conserva productos, precios, stock, ventas y cupos guardados.
-- No cambia permisos, politicas RLS, usuarios ni tablas de auth de Supabase.
-- Despues: desplegar/reiniciar el backend ACTUALIZADO y recargar la web.

BEGIN;
SET LOCAL search_path = pg_catalog, public;
SET LOCAL lock_timeout = '10s';

-- Fallar antes de cambiar datos si se eligio otra base/esquema o falta la 06.
DO $$
DECLARE requisito RECORD;
BEGIN
    FOR requisito IN
        SELECT * FROM (VALUES
            ('categorias', 'id_categoria'), ('categorias', 'nombre'),
            ('categorias', 'descripcion'), ('categorias', 'es_topping'),
            ('categorias', 'es_sabor'),
            ('productos', 'id_producto'), ('productos', 'id_categoria'),
            ('detalle_ventas', 'id_detalle'), ('detalle_ventas', 'sabores_ids'),
            ('detalle_ventas', 'sabores_detalle'), ('detalle_ventas', 'notas'),
            ('adicional_categorias', 'id_adicional'), ('adicional_categorias', 'id_categoria')
        ) AS requisitos(tabla, columna)
    LOOP
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = requisito.tabla
              AND column_name = requisito.columna
        ) THEN
            RAISE EXCEPTION 'Migracion 07 cancelada: falta public.%.%. Aplica primero las migraciones 00 a 06 completas en este proyecto.',
                requisito.tabla, requisito.columna;
        END IF;
    END LOOP;
END $$;

LOCK TABLE public.categorias, public.productos, public.detalle_ventas,
    public.adicional_categorias IN SHARE ROW EXCLUSIVE MODE;

ALTER TABLE public.categorias
    ADD COLUMN IF NOT EXISTS es_fruta BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE public.productos
    ADD COLUMN IF NOT EXISTS toppings_incluidos INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS frutas_incluidas INTEGER NOT NULL DEFAULT 0;
ALTER TABLE public.detalle_ventas
    ADD COLUMN IF NOT EXISTS frutas_detalle TEXT,
    ADD COLUMN IF NOT EXISTS frutas_ids TEXT;

-- Detectar columnas incompatibles de una instalacion manual; no convertir
-- valores ni reemplazar columnas que ya contienen informacion.
DO $$
DECLARE requisito RECORD;
BEGIN
    FOR requisito IN
        SELECT * FROM (VALUES
            ('categorias', 'es_fruta', 'bool'),
            ('productos', 'toppings_incluidos', 'int4'),
            ('productos', 'frutas_incluidas', 'int4'),
            ('detalle_ventas', 'frutas_detalle', 'text'),
            ('detalle_ventas', 'frutas_ids', 'text')
        ) AS requisitos(tabla, columna, tipo)
    LOOP
        IF NOT EXISTS (
            SELECT 1 FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = requisito.tabla
              AND column_name = requisito.columna AND udt_name = requisito.tipo
        ) THEN
            RAISE EXCEPTION 'Migracion 07 cancelada: tipo incompatible en public.%.%. Revisar esa columna sin borrar sus datos.',
                requisito.tabla, requisito.columna;
        END IF;
    END LOOP;
END $$;

-- Completar solo valores NULL de las columnas nuevas si se habian creado
-- manualmente. Los cupos ya configurados (incluido cero) no se sobrescriben.
UPDATE public.categorias SET es_fruta = FALSE WHERE es_fruta IS NULL;
ALTER TABLE public.categorias ALTER COLUMN es_fruta SET DEFAULT FALSE,
    ALTER COLUMN es_fruta SET NOT NULL;
UPDATE public.productos SET toppings_incluidos = 0 WHERE toppings_incluidos IS NULL;
UPDATE public.productos SET frutas_incluidas = 0 WHERE frutas_incluidas IS NULL;
ALTER TABLE public.productos ALTER COLUMN toppings_incluidos SET DEFAULT 0,
    ALTER COLUMN toppings_incluidos SET NOT NULL,
    ALTER COLUMN frutas_incluidas SET DEFAULT 0,
    ALTER COLUMN frutas_incluidas SET NOT NULL;

ALTER TABLE public.categorias DROP CONSTRAINT IF EXISTS ck_categoria_tipo_adicional;
ALTER TABLE public.categorias ADD CONSTRAINT ck_categoria_tipo_adicional
    CHECK (es_topping::integer + es_sabor::integer + es_fruta::integer <= 1);
ALTER TABLE public.productos DROP CONSTRAINT IF EXISTS ck_productos_cupos_incluidos;
ALTER TABLE public.productos ADD CONSTRAINT ck_productos_cupos_incluidos
    CHECK (toppings_incluidos BETWEEN 0 AND 25 AND frutas_incluidas BETWEEN 0 AND 25);

-- Conservar el ID de FRUTAS si ya existe. Una categoria vacia creada
-- manualmente puede marcarse como fruta sin mover ni reclasificar productos.
DO $$
DECLARE coincidencias INTEGER;
BEGIN
    SELECT COUNT(*) INTO coincidencias FROM public.categorias
        WHERE UPPER(BTRIM(nombre)) = 'FRUTAS';
    IF coincidencias > 1 THEN
        RAISE EXCEPTION 'Migracion 07 cancelada: existen varias categorias llamadas FRUTAS. Revisarlas sin fusionar ni borrar datos automaticamente.';
    END IF;
END $$;
UPDATE public.categorias c SET es_fruta = TRUE
WHERE UPPER(BTRIM(c.nombre)) = 'FRUTAS'
    AND NOT c.es_fruta AND NOT c.es_topping AND NOT c.es_sabor
    AND NOT EXISTS (SELECT 1 FROM public.productos p WHERE p.id_categoria = c.id_categoria)
    AND NOT EXISTS (SELECT 1 FROM public.adicional_categorias ac WHERE ac.id_categoria = c.id_categoria);
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM public.categorias
               WHERE UPPER(BTRIM(nombre)) = 'FRUTAS' AND NOT es_fruta) THEN
        RAISE EXCEPTION 'Migracion 07 cancelada: FRUTAS ya contiene datos o tiene otro tipo. Revisar la categoria existente; no borrar productos ni ventas.';
    END IF;
END $$;

-- La misma validacion de toppings y sabores, ahora tambien para frutas.
-- Se usa public explicitamente y se mantienen los permisos del usuario invocante.
CREATE OR REPLACE FUNCTION public.validar_categoria_adicional()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY INVOKER
SET search_path = pg_catalog, public
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM public.productos p
        JOIN public.categorias c USING (id_categoria)
        WHERE p.id_producto = NEW.id_adicional AND (c.es_topping OR c.es_sabor OR c.es_fruta)
    ) THEN
        RAISE EXCEPTION 'El adicional debe ser un topping, sabor o fruta';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM public.categorias WHERE id_categoria = NEW.id_categoria
            AND NOT es_topping AND NOT es_sabor AND NOT es_fruta
    ) THEN
        RAISE EXCEPTION 'El destino debe ser una categoria de productos de la carta';
    END IF;
    RETURN NEW;
END;
$$;
DROP TRIGGER IF EXISTS trg_validar_categoria_adicional ON public.adicional_categorias;
CREATE TRIGGER trg_validar_categoria_adicional
    BEFORE INSERT OR UPDATE ON public.adicional_categorias
    FOR EACH ROW EXECUTE FUNCTION public.validar_categoria_adicional();

-- Crear FRUTAS solo si falta. No usar IDs fijos ni retroceder la secuencia.
-- Las secuencias de PostgreSQL no se revierten con ROLLBACK; un error puede
-- dejar un salto de numeracion, pero no elimina ni cambia registros existentes.
DO $$
DECLARE secuencia REGCLASS; ultimo BIGINT; maximo BIGINT;
BEGIN
    IF NOT EXISTS (SELECT 1 FROM public.categorias WHERE UPPER(BTRIM(nombre)) = 'FRUTAS') THEN
        secuencia := pg_get_serial_sequence('public.categorias', 'id_categoria')::regclass;
        IF secuencia IS NULL THEN
            RAISE EXCEPTION 'Migracion 07 cancelada: public.categorias.id_categoria necesita una secuencia SERIAL o IDENTITY para crear FRUTAS.';
        END IF;
        EXECUTE format('SELECT last_value FROM %s', secuencia) INTO ultimo;
        SELECT COALESCE(MAX(id_categoria), 0) INTO maximo FROM public.categorias;
        PERFORM setval(secuencia, GREATEST(ultimo, maximo, 1), TRUE);
        INSERT INTO public.categorias(nombre, descripcion, es_topping, es_sabor, es_fruta)
        VALUES ('FRUTAS', 'Frutas adicionales con precio configurable', FALSE, FALSE, TRUE);
    END IF;
END $$;

COMMENT ON COLUMN public.categorias.es_fruta IS 'Catalogo de frutas adicionales; no se vende como producto base.';
COMMENT ON COLUMN public.productos.toppings_incluidos IS 'Primeras selecciones de toppings incluidas por unidad; extras a precio de catalogo.';
COMMENT ON COLUMN public.productos.frutas_incluidas IS 'Primeras selecciones de frutas incluidas por unidad; independiente de toppings.';
COMMENT ON COLUMN public.detalle_ventas.frutas_detalle IS 'Nombres de frutas al vender; preserva el comprobante historico.';
COMMENT ON COLUMN public.detalle_ventas.frutas_ids IS 'Ids CSV para restituir stock al anular, incluidas las frutas sin cobro.';

COMMIT;

-- Resultado esperado en SQL Editor: una fila FRUTAS con es_fruta = true.
SELECT 'Migracion 07 aplicada' AS resultado,
    id_categoria, nombre, es_topping, es_sabor, es_fruta
FROM public.categorias WHERE UPPER(BTRIM(nombre)) = 'FRUTAS';

-- DESPUES DE EJECUTAR:
-- 1. Desplegar/reiniciar el backend Java actualizado que apunta a ESTE proyecto Supabase.
-- 2. Recargar Inventario. /api/categorias debe devolver esFruta: true para FRUTAS.
-- 3. Crear frutas (precio cero o de pago) desde Agregar toppings > FRUTAS.
--    Para reclasificar frutas existentes: editarlas y conservar sus destinos.
-- 4. Configuracion > categoria > producto > guardar cupos. Empiezan en cero
--    en productos nuevos; los ya configurados conservan sus cantidades.
-- La migracion NO deduce promociones del nombre ni aplica cupos a toda una categoria.
-- Si aparece un error: no ejecutar lineas sueltas. Si la sesion queda en una
-- transaccion abortada, ejecutar ROLLBACK; resolver la causa y repetir TODO.
