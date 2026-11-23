-- =====================================================================
-- MIGRACION 02 - Recuperacion de toppings historicos y correlativo
--
-- Es idempotente: se puede correr las veces que haga falta.
--
-- Que resuelve:
--
-- 1) toppings_ids vacio en ventas viejas
--    Cuando se guardo la venta solo se registro el TEXTO del topping
--    ("Chips de chocolate"). El id nunca se guardo, asi que al anular
--    una venta vieja no se podia devolver el topping al stock: solo
--    teniamos su nombre, no su identidad.
--    Los nombres si coinciden exactamente con productos.nombre_producto
--    (verificado uno por uno), asi que se puede reconstruir el id de
--    forma segura sin adivinar nada.
--
-- 2) Salto en el correlativo
--    Una venta de prueba se borro y dejo la secuencia en 11, cuando la
--    ultima venta real es la 10. Se realinea para que el proximo
--    comprobante sea el 11 y no haya huecos.
-- =====================================================================

BEGIN;

-- ---------------------------------------------------------------------
-- 1) Reconstruir toppings_ids a partir de toppings_detalle
--
--    toppings_ids es un CSV ("21,24"), que es lo que espera el codigo
--    (VentaService.idsToppings hace split por ","). Se recorre el texto
--    con string_to_array respetando el orden original y se busca el
--    topping por nombre exacto.
--
--    OJO: el nombre se busca entre TODOS los toppings (categorias 5 y 7),
--    no solo entre los que hoy figuran en producto_toppings para ese
--    producto. La tabla producto_toppings cambio varias veces con los
--    menus y las ventas viejas tienen toppings que ya no estan
--    configurados para su producto, pero el nombre del topping si
--    existe y es unico, asi que el id se recupera igual.
--
--    Un topping suelto (sin texto) se queda como NULL: significa
--    "no llevaba toppings", no "no sabemos".
-- ---------------------------------------------------------------------

WITH partes AS (
    -- separa "A, B" en filas, con su posicion para conservar el orden
    SELECT
        d.id_detalle,
        BTRIM(p.nombre) AS nombre,
        p.ordinality    AS posicion
    FROM detalle_ventas d,
         LATERAL UNNEST(
             STRING_TO_ARRAY(d.toppings_detalle, ',')
         ) WITH ORDINALITY AS p(nombre, ordinality)
    WHERE NULLIF(BTRIM(d.toppings_detalle), '') IS NOT NULL
),
resueltos AS (
    SELECT
        pa.id_detalle,
        pa.posicion,
        -- nombre exacto entre todos los toppings. Los nombres de las
        -- categorias 5 y 7 son unicos, asi que no hay ambiguedad.
        (SELECT MIN(t.id_producto)
         FROM productos t
         WHERE t.id_categoria IN (5, 7)
           AND t.nombre_producto = pa.nombre
        ) AS id_topping
    FROM partes pa
)
UPDATE detalle_ventas d
SET toppings_ids = sub.ids_csv
FROM (
    SELECT
        id_detalle,
        STRING_AGG(id_topping::text, ',' ORDER BY posicion) AS ids_csv
    FROM resueltos
    WHERE id_topping IS NOT NULL
    GROUP BY id_detalle
) sub
WHERE d.id_detalle = sub.id_detalle
  AND d.toppings_ids IS NULL;

-- Si el texto de un topping NO se pudo mapear a un id, se avisa en vez
-- de dejar un valor a medias que devuelva stock equivocado.
DO $$
DECLARE
    sin_mapear TEXT;
BEGIN
    SELECT STRING_AGG(
        'detalle ' || d.id_detalle || ': "' || d.toppings_detalle || '"', E'\n  ')
    INTO sin_mapear
    FROM detalle_ventas d
    WHERE NULLIF(BTRIM(d.toppings_detalle), '') IS NOT NULL
      AND NOT EXISTS (
          SELECT 1
          FROM productos t
          WHERE t.id_categoria IN (5, 7)
            AND t.nombre_producto = ANY(
                STRING_TO_ARRAY(d.toppings_detalle, ','))
      );

    IF sin_mapear IS NOT NULL THEN
        RAISE WARNING ' toppings historicos que NO se pudieron mapear a id:%', E'\n  ' || sin_mapear;
    END IF;
END $$;

-- Los renglones que de verdad no llevaban toppings se dejan en NULL
-- explicitamente (no en cadena vacia), para que el codigo los trate
-- igual que antes.
UPDATE detalle_ventas
SET toppings_ids = NULL
WHERE NULLIF(BTRIM(COALESCE(toppings_ids, '')), '') IS NULL;

COMMENT ON COLUMN detalle_ventas.toppings_ids IS
    'CSV de ids de topping (ej. "21,24"). Permite devolver el topping al stock al anular. Reconstruido desde toppings_detalle en la migracion 02.';


-- ---------------------------------------------------------------------
-- 2) Realinear los correlativos
--
--    La venta de prueba dejo la secuencia en 11 pero la ultima venta real
--    es la 10. Se pone la secuencia en el MAX(id_venta) para que el
--    proximo comprobante siga a la ultima venta existente.
--
--    OJO con el tercer argumento de setval (is_called), que es la clave
--    de que esto funcione en una base recien creada:
--
--      - hay ventas  -> setval(seq, 10, true)  -> el siguiente es 11  OK
--      - no hay nada -> setval(seq,  1, false) -> el siguiente es  1  OK
--
--    Con setval(seq, 1, true) en una base vacia el siguiente sale 2 y
--    la primera venta queda con correlativo 2. Ese error hacia que la
--    primera boleta se emitiera como B001-000002.
-- ---------------------------------------------------------------------

SELECT setval(
    'ventas_numero_correlativo_seq',
    GREATEST(COALESCE((SELECT MAX(id_venta) FROM ventas), 0), 1),
    COALESCE((SELECT MAX(id_venta) FROM ventas), 0) > 0
);

-- La misma logica para la nota de crédito, por si quedó desalineada.
SELECT setval(
    'seq_nota_credito',
    GREATEST(COALESCE((SELECT MAX(id_nota_credito) FROM notas_credito), 0), 1),
    COALESCE((SELECT MAX(id_nota_credito) FROM notas_credito), 0) > 0
);

COMMIT;

-- =====================================================================
-- VERIFICACION
--   1) No debe salir ninguna fila con texto de topping pero sin id mapeado.
--   2) La secuencia debe quedar en la ultima venta real (10).
-- =====================================================================
-- SELECT d.id_detalle, d.toppings_detalle, d.toppings_ids
-- FROM detalle_ventas d
-- WHERE NULLIF(BTRIM(d.toppings_detalle), '') IS NOT NULL
--   AND NULLIF(BTRIM(d.toppings_ids), '') IS NULL;
--
-- SELECT last_value FROM ventas_numero_correlativo_seq;
-- SELECT MAX(id_venta) FROM ventas;
-- =====================================================================
