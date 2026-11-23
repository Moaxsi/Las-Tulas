-- Evita que un reintento del mismo cobro cree otra venta o descuente stock dos veces.
-- Ejecutar despues de migracion_04_login_seguridad.sql.

BEGIN;

ALTER TABLE ventas
    ADD COLUMN IF NOT EXISTS clave_idempotencia VARCHAR(36),
    ADD COLUMN IF NOT EXISTS huella_idempotencia VARCHAR(64),
    ADD COLUMN IF NOT EXISTS tasa_igv NUMERIC(10,6);

CREATE UNIQUE INDEX IF NOT EXISTS ux_ventas_clave_idempotencia
    ON ventas (clave_idempotencia)
    WHERE clave_idempotencia IS NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'ck_ventas_idempotencia_pair'
    ) THEN
        ALTER TABLE ventas
            ADD CONSTRAINT ck_ventas_idempotencia_pair
            CHECK (
                (clave_idempotencia IS NULL AND huella_idempotencia IS NULL)
                OR (clave_idempotencia IS NOT NULL AND huella_idempotencia IS NOT NULL)
            );
    END IF;
END $$;

COMMENT ON COLUMN ventas.clave_idempotencia IS
    'UUID del intento de cobro; UNIQUE para que el reintento no duplique la venta.';
COMMENT ON COLUMN ventas.huella_idempotencia IS
    'SHA-256 del cuerpo del cobro para impedir reutilizar la clave con otros datos.';
COMMENT ON COLUMN ventas.tasa_igv IS
    'Tasa aplicada al cobro; preserva el desglose original para consulta y reintentos.';

COMMIT;
