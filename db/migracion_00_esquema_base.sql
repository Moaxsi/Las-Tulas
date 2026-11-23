-- =====================================================================
-- LAS TULAS - Migracion 00: ESQUEMA BASE
-- ---------------------------------------------------------------------
-- Esta es la PRIMERA migracion que se corre. Crea las tablas del
-- proyecto original, con los nombres y tipos de columna reales.
--
-- ORDEN DE EJECUCION (no cambiar):
--     1. migracion_00_esquema_base.sql
--     2. migracion_01_cimientos.sql
--     3. migracion_02_toppings_correlativo.sql
--
-- Para que el backend arranque hace falta ademas el dato de negocio
-- (categorias, catalogo de productos). Eso va aparte, en
-- datos_catalogo_inicial.sql, porque es informacion que cambia con la
-- carta y no debe mezclarse con la estructura.
--
-- Importante: la 01 y la 02 SOLO alteran tablas. Si te saltas esta
-- migracion, la 01 falla con "relation productos does not exist".
--
-- Es idempotente: correrla dos veces no rompe nada.
-- =====================================================================

BEGIN;

-- ---------------------------------------------------------------------
-- Tablas de catalogo y apoyo
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS categorias (
    id_categoria   SERIAL PRIMARY KEY,
    nombre         VARCHAR(50) NOT NULL,
    descripcion    TEXT,
    CONSTRAINT categorias_nombre_key UNIQUE (nombre)
);

CREATE TABLE IF NOT EXISTS roles (
    id_rol         SERIAL PRIMARY KEY,
    nombre         VARCHAR(30) NOT NULL,
    CONSTRAINT roles_nombre_key UNIQUE (nombre)
);

CREATE TABLE IF NOT EXISTS tipos_comprobante (
    id_tipo_comprobante   SERIAL PRIMARY KEY,
    nombre_comprobante    VARCHAR(50) NOT NULL,
    CONSTRAINT tipos_comprobante_nombre_comprobante_key UNIQUE (nombre_comprobante)
);

CREATE TABLE IF NOT EXISTS metodos_pago (
    id_metodo_pago SERIAL PRIMARY KEY,
    nombre_metodo  VARCHAR(50) NOT NULL,
    CONSTRAINT metodos_pago_nombre_metodo_key UNIQUE (nombre_metodo)
);

CREATE TABLE IF NOT EXISTS clientes (
    id_cliente          SERIAL PRIMARY KEY,
    tipo_documento      VARCHAR(20) DEFAULT 'SIN_DOC',
    numero_documento    VARCHAR(20),
    nombre_razon_social VARCHAR(150) DEFAULT 'CLIENTE GENERAL' NOT NULL,
    direccion           VARCHAR(200),
    email               VARCHAR(100),
    fecha_registro      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT clientes_tipo_documento_check
        CHECK (tipo_documento IN ('SIN_DOC', 'DNI', 'RUC'))
);

-- ---------------------------------------------------------------------
-- Productos
--
-- productos.estado NO se elige a mano: se deriva del stock. Los umbrales
-- estan en ProductoEstado.java y la migracion 01 pone al dia lo que
-- quedara desfasado.
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS productos (
    id_producto     SERIAL PRIMARY KEY,
    referencia      VARCHAR(30) NOT NULL,
    nombre_producto VARCHAR(150) NOT NULL,
    descripcion     TEXT,
    precio          NUMERIC(10,2) NOT NULL,
    stock           INTEGER DEFAULT 0,
    id_categoria    INTEGER NOT NULL,
    estado          VARCHAR(20) DEFAULT 'Disponible',
    url_imagen      TEXT DEFAULT 'lastulas.jpg',
    fecha_creacion  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT productos_referencia_key UNIQUE (referencia),
    CONSTRAINT productos_estado_check
        CHECK (estado IN ('Disponible', 'Stock bajo', 'Agotado')),
    CONSTRAINT productos_precio_check CHECK (precio >= 0),
    CONSTRAINT productos_stock_check  CHECK (stock  >= 0),
    CONSTRAINT fk_producto_categoria FOREIGN KEY (id_categoria)
        REFERENCES categorias (id_categoria)
);

-- Relacion producto <-> topping que admite.
-- Un topping es un producto de las categorias 5 (TOPPIN) y 7 (TOPPING),
-- por eso la clave foranea de id_topping apunta a productos.id_producto.
CREATE TABLE IF NOT EXISTS producto_toppings (
    id_producto INTEGER NOT NULL,
    id_topping  INTEGER NOT NULL,
    CONSTRAINT producto_toppings_pkey PRIMARY KEY (id_producto, id_topping),
    CONSTRAINT producto_toppings_id_producto_fkey FOREIGN KEY (id_producto)
        REFERENCES productos (id_producto) ON DELETE CASCADE,
    CONSTRAINT producto_toppings_id_topping_fkey FOREIGN KEY (id_topping)
        REFERENCES productos (id_producto) ON DELETE CASCADE
);

-- ---------------------------------------------------------------------
-- Usuarios
--
-- codigo_pin es el PIN en texto plano y sigue asi a proposito: es lo que
-- tiene el proyecto original. La migracion 01 agrega pin_hash y el
-- backend hashea el PIN en el primer login exitoso, asi que no hay que
-- pedirle a nadie que reinscriba su PIN.
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS usuarios (
    id_usuario     SERIAL PRIMARY KEY,
    nombre_completo VARCHAR(120) NOT NULL,
    usuario_login  VARCHAR(50) NOT NULL,
    codigo_pin     VARCHAR(10) NOT NULL,
    id_rol         INTEGER NOT NULL,
    estado         VARCHAR(20) DEFAULT 'Activo',
    fecha_creacion TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT usuarios_usuario_login_key UNIQUE (usuario_login),
    CONSTRAINT usuarios_estado_check CHECK (estado IN ('Activo', 'Inactivo')),
    CONSTRAINT fk_usuario_rol FOREIGN KEY (id_rol) REFERENCES roles (id_rol)
);

-- ---------------------------------------------------------------------
-- Ventas
--
-- OJO: todavia NO existen estado, subtotal, igv, vuelto ni los campos de
-- anulacion. Eso los agrega la migracion 01, que tambien hace el backfill
-- de los valores historicos. Si las pones aqui, el backfill no tendria
-- nada que rellenar.
-- ---------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS ventas (
    id_venta            SERIAL PRIMARY KEY,
    numero_serie        VARCHAR(10) DEFAULT 'B001',
    numero_correlativo  INTEGER NOT NULL,
    id_usuario          INTEGER DEFAULT 2,
    cliente_nombre      VARCHAR(150) DEFAULT 'CLIENTE GENERAL' NOT NULL,
    cliente_documento   VARCHAR(20) DEFAULT '',
    tipo_comprobante    VARCHAR(50) NOT NULL,
    metodo_pago         VARCHAR(50) NOT NULL,
    monto_efectivo      NUMERIC(10,2) DEFAULT 0.00,
    monto_digital       NUMERIC(10,2) DEFAULT 0.00,
    total               NUMERIC(10,2) NOT NULL,
    fecha_venta         TIMESTAMP DEFAULT CURRENT_TIMESTAMP
    -- id_usuario no lleva clave foranea a proposito: asi estaba en el
    -- proyecto original y hay ventas historicas de usuarios ya borrados.
    -- Si se pusiera el FK, esas ventas no se podrian leer.
);

-- Secuencia propia del correlativo del comprobante, separada de
-- id_venta. El correlativo es el numero que ve el cliente (B001-000001)
-- y tiene que ser consecutivo, sin depender de que id_venta se haya
-- borrado alguna vez. Por eso la migracion 02 puede realinearlo.
CREATE SEQUENCE IF NOT EXISTS ventas_numero_correlativo_seq
    START WITH 1 INCREMENT BY 1 OWNED BY ventas.numero_correlativo;

-- El correlativo tambien sale de la secuencia si alguien inserta una venta
-- sin indicar el numero (por ejemplo un script o un importador).
ALTER TABLE ventas ALTER COLUMN numero_correlativo
    SET DEFAULT nextval('ventas_numero_correlativo_seq');

CREATE TABLE IF NOT EXISTS detalle_ventas (
    id_detalle       SERIAL PRIMARY KEY,
    id_venta         INTEGER NOT NULL,
    id_producto      INTEGER,
    nombre_producto  VARCHAR(150) NOT NULL,
    cantidad         INTEGER NOT NULL,
    precio_unitario  NUMERIC(10,2) NOT NULL,
    subtotal         NUMERIC(10,2) NOT NULL,
    toppings_detalle TEXT DEFAULT '',
    CONSTRAINT fk_detalle_venta FOREIGN KEY (id_venta)
        REFERENCES ventas (id_venta) ON DELETE CASCADE
);

-- ---------------------------------------------------------------------
-- Datos minimos para que el backend pueda arrancar
--
-- Sin roles y metodos de pago la app levanta pero no hay con quien
-- iniciar sesion ni como cobrar. Los PIN van en texto plano a proposito:
-- la migracion 01 y el backend se encargan de hashearlos.
-- ---------------------------------------------------------------------

INSERT INTO roles (nombre) VALUES ('ADMIN'), ('CAJERO')
ON CONFLICT (nombre) DO NOTHING;

INSERT INTO tipos_comprobante (nombre_comprobante) VALUES
    ('Boleta simple'),
    ('Boleta con DNI'),
    ('Factura')
ON CONFLICT (nombre_comprobante) DO NOTHING;

INSERT INTO metodos_pago (nombre_metodo) VALUES
    ('Efectivo'), ('POS'), ('Yape'), ('Plin')
ON CONFLICT (nombre_metodo) DO NOTHING;

-- Usuario administrador para poder entrar por primera vez.
-- PIN: 1234. Cambialo en cuanto entres.
INSERT INTO usuarios (nombre_completo, usuario_login, codigo_pin, id_rol, estado)
SELECT 'Administrador Principal', 'admin', '1234', r.id_rol, 'Activo'
FROM roles r WHERE r.nombre = 'ADMIN'
ON CONFLICT (usuario_login) DO NOTHING;

COMMIT;

-- =====================================================================
-- VERIFICACION
-- =====================================================================
-- SELECT COUNT(*) FROM information_schema.tables
-- WHERE table_schema = 'public';            -- esperado: 12
-- SELECT * FROM roles;
-- SELECT * FROM usuarios;
-- =====================================================================
