-- =====================================================================
-- LAS TULAS - Migracion 04: SEGURIDAD DE LOGIN
-- ---------------------------------------------------------------------
-- Añade campos para rate limiting y bloqueo de cuentas por intentos fallidos.
-- Se ejecuta DESPUÉS de la migración 03.
-- =====================================================================

BEGIN;

-- Campos para tracking de intentos fallidos y bloqueo temporal
ALTER TABLE usuarios
    ADD COLUMN IF NOT EXISTS intentos_fallidos INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS bloqueado_hasta TIMESTAMP;

-- Índice para consultas rápidas de usuarios bloqueados
CREATE INDEX IF NOT EXISTS idx_usuarios_bloqueado_hasta ON usuarios (bloqueado_hasta)
    WHERE bloqueado_hasta IS NOT NULL;

COMMIT;

-- =====================================================================
-- VERIFICACION
-- =====================================================================
-- \d usuarios
-- Debe mostrar: intentos_fallidos (int4, not null, default 0), bloqueado_hasta (timestamp)
-- =====================================================================