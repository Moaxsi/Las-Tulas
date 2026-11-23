-- Requisito: migraciones 00 a 07. Ejecutar antes de reiniciar el backend.
-- Conserva ventas anteriores sin inventar sus precios históricos.
BEGIN;
SET LOCAL search_path = pg_catalog, public;
SET LOCAL lock_timeout = '10s';

ALTER TABLE public.detalle_ventas
    ADD COLUMN IF NOT EXISTS precio_base_unitario NUMERIC(10,2),
    ADD COLUMN IF NOT EXISTS adicionales_cobrados TEXT;

CREATE SEQUENCE IF NOT EXISTS public.ventas_nota_venta_correlativo_seq
    START WITH 1 INCREMENT BY 1;

COMMENT ON COLUMN public.detalle_ventas.precio_base_unitario IS
    'Precio base al cobrar; NULL para ventas anteriores sin desglose verificable.';
COMMENT ON COLUMN public.detalle_ventas.adicionales_cobrados IS
    'JSON de adicionales efectivamente cobrados por unidad: idProducto, nombreProducto y precioUnitario. Excluye incluidos y gratuitos.';
COMMENT ON SEQUENCE public.ventas_nota_venta_correlativo_seq IS
    'Numeración interna NV01 para notas de venta; independiente del correlativo de boletas.';
COMMIT;
