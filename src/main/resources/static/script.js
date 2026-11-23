// URL base de la API Spring Boot
const API_URL = '/api';

// =====================================================
// HELPERS DE SEGURIDAD Y UTILIDAD
// =====================================================
// Escape de HTML para prevenir XSS con datos traídos de la base de datos.
function esc(s) {
    return String(s ?? '').replace(/[&<>"']/g, c =>
        ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

// Avisos y confirmaciones propios: texto seguro y foco administrado por dialog.
function mostrarAvisoSistema(mensaje, tipo = 'info') {
    const contenedor = [...document.querySelectorAll('dialog[open]')].pop() || document.body;
    document.querySelectorAll('.system-notice').forEach(aviso => aviso.remove());
    const aviso = document.createElement('div');
    aviso.className = `system-notice system-notice--${tipo}`;
    const texto = document.createElement('p');
    texto.setAttribute('role', tipo === 'error' ? 'alert' : 'status');
    texto.textContent = String(mensaje);
    const cerrar = document.createElement('button');
    cerrar.type = 'button'; cerrar.className = 'system-notice-close';
    cerrar.setAttribute('aria-label', 'Cerrar mensaje'); cerrar.textContent = '×';
    cerrar.onclick = () => aviso.remove();
    aviso.append(texto, cerrar);
    contenedor.append(aviso);
    setTimeout(() => aviso.remove(), Math.max(5000, Math.min(12000, String(mensaje).length * 55)));
}

function confirmarSistema(mensaje, accion = 'Confirmar') {
    return new Promise(resolve => {
        const dialogo = document.createElement('dialog');
        dialogo.className = 'system-confirm-dialog inventory-category-dialog';
        dialogo.setAttribute('aria-labelledby', 'system-confirm-title');
        dialogo.setAttribute('aria-describedby', 'system-confirm-message');
        dialogo.innerHTML = `<form method="dialog" class="modal-content-card system-confirm-panel">
            <h3 id="system-confirm-title">Confirmar acción</h3>
            <p id="system-confirm-message"></p>
            <div class="modal-footer"><button class="btn-cancel" value="cancelar" autofocus>Cancelar</button>
            <button class="btn-primary-action" value="confirmar"></button></div></form>`;
        dialogo.querySelector('p').textContent = mensaje;
        dialogo.querySelector('.btn-primary-action').textContent = accion;
        const anterior = document.activeElement;
        let finalizado = false;
        const finalizar = confirmado => {
            if (finalizado) return;
            finalizado = true;
            if (dialogo.open) dialogo.close();
            dialogo.remove();
            if (anterior?.isConnected) anterior.focus({ preventScroll: true });
            resolve(confirmado);
        };
        dialogo.querySelector('form').addEventListener('submit', event => {
            event.preventDefault();
            finalizar(event.submitter?.value === 'confirmar');
        });
        dialogo.addEventListener('cancel', event => {
            event.preventDefault();
            finalizar(false);
        });
        dialogo.addEventListener('close', () => finalizar(false), { once: true });
        document.body.append(dialogo);
        dialogo.showModal();
    });
}

// =====================================================
// PUNTO 10 - CARRITO PERSISTENTE
// =====================================================
// El carrito vivia en sessionStorage, que se vacia al cerrar la pestana. Un
// cliente que armaba un pedido grande y recargaba la pagina perdia todo lo
// que habia elegido, sin previo aviso.
//
// Ahora va en localStorage: sobrevive al F5, al cierre de pestana y al logout
// (que solo borra el token, no el pedido). La contra es que el carrito queda
// en la maquina, asi que al iniciar sesion se avisa y al logout se ofrece
// vaciarlo. El precio SIEMPRE se recalcula en el servidor, asi que un carrito
// viejo no puede cobrar precios viejos: solo se pierde la seleccion, no el
// control del dinero.
const CLAVE_CARRITO = 'pos_carrito';
const CLAVE_COBRO_PENDIENTE = 'pos_cobro_pendiente';

function parseCarrito() {
    try {
        // Migracion: si todavia esta en sessionStorage de una version vieja,
        // se pasa a localStorage y se borra el original para no tener dos
        // carritos dando vueltas con distinto contenido.
        const viejo = sessionStorage.getItem(CLAVE_CARRITO);
        if (viejo) {
            localStorage.setItem(CLAVE_CARRITO, viejo);
            sessionStorage.removeItem(CLAVE_CARRITO);
        }
        const guardado = JSON.parse(localStorage.getItem(CLAVE_CARRITO));
        return Array.isArray(guardado) ? guardado : [];
    } catch (e) {
        return [];
    }
}

function guardarCarrito() {
    try {
        localStorage.setItem(CLAVE_CARRITO, JSON.stringify(posCarrito));
    } catch (e) {
        // Cuota llena o almacenamiento bloqueado: el POS sigue funcionando
        // en memoria, solo no sobrevive al F5. No es motivo para frenarlo.
        console.warn('No se pudo guardar el carrito:', e);
    }
}

function idProductoPOS(producto) {
    const id = producto?.idProducto ?? producto?.id_producto;
    return id === null || id === undefined ? null : String(id);
}

// =====================================================
// AUTENTICACION CON JWT (punto 9)
// =====================================================
// A partir de ahora el navegador guarda un token y lo manda en cada llamada.
// El sessionStorage ya no decide quien entra: solo evita pedir login dos veces.
// La verdad esta en el backend, que responde 401 si el token falta o expiro.

const CLAVE_TOKEN = 'jwt_token';

function obtenerToken() {
    return sessionStorage.getItem(CLAVE_TOKEN) || localStorage.getItem(CLAVE_TOKEN);
}

function obtenerSesionItem(clave) {
    return sessionStorage.getItem(clave) || localStorage.getItem(clave);
}

function guardarSesion(data) {
    sessionStorage.setItem(CLAVE_TOKEN, data.token);
    sessionStorage.setItem('usuario_rol', data.user.rol_nombre);
    sessionStorage.setItem('usuario_id', data.user.idUsuario);
    sessionStorage.setItem('usuario_nombre', data.user.nombreCompleto);

    // Respaldo en localStorage para que en móviles no se cierre la sesión al apagar la pantalla o cambiar de pestaña
    try {
        localStorage.setItem(CLAVE_TOKEN, data.token);
        localStorage.setItem('usuario_rol', data.user.rol_nombre);
        localStorage.setItem('usuario_id', data.user.idUsuario);
        localStorage.setItem('usuario_nombre', data.user.nombreCompleto);
    } catch (e) {
        // Storage bloqueado o modo incógnito
    }
}

// Cierre de sesión: limpia el token y la identidad local, pero NO el carrito.
// Un pedido armado no se pierde por cerrar sesión; lo que se ofrece es vaciarlo
// explícitamente desde la pantalla de caja.
function cerrarSesion(mensaje) {
    sessionStorage.removeItem(CLAVE_TOKEN);
    sessionStorage.removeItem('usuario_rol');
    sessionStorage.removeItem('usuario_id');
    sessionStorage.removeItem('usuario_nombre');

    try {
        localStorage.removeItem(CLAVE_TOKEN);
        localStorage.removeItem('usuario_rol');
        localStorage.removeItem('usuario_id');
        localStorage.removeItem('usuario_nombre');
    } catch (e) {}

    if (mensaje) sessionStorage.setItem('avisos_logout', mensaje);
    window.location.href = 'index.html';
}

/**
 * Envuelve fetch para todo lo que va contra /api: pega el token, y convierte
 * un 401 en "sesion caida" y un 403 en "no autorizado" en lugar de dejar que
 * cada pantalla adivine que paso.
 */
async function apiFetch(ruta, opciones = {}) {
    const cabeceras = Object.assign({}, opciones.headers || {});
    const token = obtenerToken();
    if (token) cabeceras['Authorization'] = 'Bearer ' + token;
    if (opciones.body && !cabeceras['Content-Type']) cabeceras['Content-Type'] = 'application/json';
    cabeceras['X-Pinggy-No-Screen'] = 'true';

    const respuesta = await fetch(API_URL + ruta, Object.assign({}, opciones, { headers: cabeceras }));

    if (respuesta.status === 401) {
        const error = new Error('SESION_EXPIRADA');
        error.esSesionExpirada = true;
        cerrarSesion('Tu sesion expiro o no inicio sesion. Vuelve a entrar.');
        throw error;
    }
    if (respuesta.status === 403) {
        const error = new Error('SIN_PERMISOS');
        error.esSinPermisos = true;
        throw error;
    }
    return respuesta;
}

/**
 * Un solo lugar para los errores de red. Se traga los de sesion expirada para
 * no soltar un alert encima de la pantalla de login.
 */
function errorControlado(err, mensajePorDefecto) {
    if (err && (err.esSesionExpirada || err.esSinPermisos)) return;
    console.error(err);
    mostrarAvisoSistema(mensajePorDefecto, 'error');
}

function navegarConTransicion(destino) {
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
        window.location.assign(destino);
        return;
    }
    if (document.body.classList.contains('is-leaving')) return;
    document.body.classList.add('is-leaving');
    window.setTimeout(() => window.location.assign(destino), 150);
}

function iniciarTransicionesPagina() {
    document.addEventListener('click', (event) => {
        if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
        const link = event.target.closest?.('a[href]');
        if (!link || link.target === '_blank' || link.hasAttribute('download')) return;

        const url = new URL(link.href, window.location.href);
        if (url.origin !== window.location.origin || !url.pathname.toLowerCase().endsWith('.html')) return;
        if (url.pathname === window.location.pathname && url.search === window.location.search && url.hash === window.location.hash) return;

        event.preventDefault();
        navegarConTransicion(url.href);
    });
}

// Guard de acceso rápido: si no hay token, ni siquiera se intenta cargar nada.
// El backend igual responde 401; esto solo evita el parpadeo.
function permitirSesion(rolesPermitidos) {
    if (!obtenerToken()) {
        window.location.href = 'index.html';
        return false;
    }
    const rol = sessionStorage.getItem('usuario_rol');
    if (!rol || !rolesPermitidos.includes(rol)) {
        window.location.href = 'index.html';
        return false;
    }
    return true;
}

function configurarBarraLateral(rol) {
    const titulo = document.querySelector('.sidebar-role-title');
    if (titulo) titulo.innerText = rol === 'ADMIN' ? 'ADMINISTRADOR' : 'CAJERO';

    document.querySelectorAll('.sidebar-menu [data-roles]').forEach(item => {
        const rolesPermitidos = item.dataset.roles.split(',');
        item.hidden = !rolesPermitidos.includes(rol);
    });
}

// El DNI peruano no usa un dígito verificador público; el RUC sí.
function validarDocumentoPeru(tipo, numero) {
    if (tipo === 'dni') {
        return /^[0-9]{8}$/.test(numero) || /^[0-9]{9,12}$/.test(numero);
    }
    if (tipo === 'ruc') {
        if (!/^[0-9]{11}$/.test(numero)) return false;
        const pesos = [5, 4, 3, 2, 7, 6, 5, 4, 3, 2];
        let suma = 0;
        for (let i = 0; i < 10; i++) suma += parseInt(numero[i], 10) * pesos[i];
        const verificador = 11 - (suma % 11);
        const dig = verificador === 10 ? '0' : (verificador === 11 ? '1' : String(verificador));
        return dig === numero[10];
    }
    return false;
}

// ESTADO GLOBAL
let rolSeleccionado = '';
let pinActual = '';
let ventaEnviando = false;
let imagenActual = 'lastulas.jpg';
let productosLista = [];
let categoriasInventario = new Map();
let idProductoAEliminar = null;

// ESTADO DEL POS (CAJERO)
let posCarrito = parseCarrito();
let itemPosSeleccionado = posCarrito[0] || null;
let productoPosSeleccionado = posCarrito[0]?.producto || null;
let toppingsSeleccionados = [...(posCarrito[0]?.toppings || [])];
let categoriaPOSActual = 0;
// Metodo de pago y tipo de comprobante se guardan como CODIGO canonico
// (punto 7). Antes se mandaba el texto visible ('Efectivo', 'Boleta con DNI')
// y la columna de la base guardaba etiquetas distintas segun quien cobraba.
let metodoPagoActual = 'EFECTIVO';
let comprobanteActual = 'NOTA_VENTA';
// Tipos de adicionales y categorias de destino, cargados desde el catalogo.
let categoriasToppingIds = new Set();
let categoriasSaborIds = new Set();
let categoriasFrutaIds = new Set();
let frutasSeleccionadas = [...(posCarrito[0]?.frutas || [])];
let categoriasCatalogo = [];
let saboresSeleccionados = [...(posCarrito[0]?.sabores || [])];
let tipoAdicionalPOS = 'toppings';
let opcionesCantidadPOS = [];
let formularioAdicionalInventario = false;
let digitacionPago = '';
let campoActivoTeclado = 'efectivo';

// ESTADO DE REPORTES Y USUARIOS
let boletasLista = [];
let cajeroFiltroActivo = 'TODOS';
let cajerosDisponiblesLista = [];
let boletaSeleccionadaAnulacion = null;
let usuariosCajerosLista = [];

// =====================================================
// PUNTO 12 - IGV DINAMICO
// =====================================================
// La tasa no esta escrita en el JavaScript: se pide a /api/config, que la
// lee de la tabla parametros. Si SUNAT cambia el porcentaje se actualiza esa
// fila y el desglose de toda la app se mueve solo, sin tocar este archivo.

const TASA_IGV_POR_DEFECTO = 0.18;
let tasaIgvActual = TASA_IGV_POR_DEFECTO;

async function cargarConfigNegocio() {
    try {
        // /api/config es publico justamente para esto: el POS necesita la tasa
        // para pintar el desglose antes de que exista el cobro.
        const res = await fetch(`${API_URL}/config`, {
            headers: { 'X-Pinggy-No-Screen': 'true' }
        });
        if (!res.ok) return;
        const cfg = await res.json();
        if (cfg && cfg.tasaIgv !== undefined && cfg.tasaIgv !== null) {
            tasaIgvActual = Number(cfg.tasaIgv);
        }
    } catch (err) {
        // Si no se puede leer, se sigue con el 18% por defecto: es preferible
        // mostrar un desglose aproximado que romper la pantalla de caja.
        console.warn('No se pudo leer la tasa de IGV, se usa ' + TASA_IGV_POR_DEFECTO, err);
    }
}

/**
 * Parte un total que YA incluye IGV en base imponible e impuesto.
 * El precio de carta es el precio final, asi que aqui no se multiplica nada:
 * el total nunca cambia, solo se desarma.
 */
function desglosarIgv(total) {
    const totalNum = Number(total) || 0;
    const base = totalNum / (1 + tasaIgvActual);
    const impuesto = totalNum - base;
    return {
        total: totalNum,
        base: base,
        igv: impuesto,
        porcentaje: tasaIgvActual * 100
    };
}

function fmtDinero(valor) {
    return 'S/ ' + (Number(valor) || 0).toFixed(2);
}

/**
 * Badge del estado de un comprobante. Una boleta anulada ya no es una venta
 * emitida: se pinta en rojo y tachada para que el admin no la lea por error
 * como si siguiera contando en la caja.
 */
function badgeEstadoComprobante(estado) {
    const texto = (estado || 'EMITIDA').toUpperCase();
    const anulada = texto === 'ANULADA';
    return `<span class="status-badge ${anulada ? 'anulada' : 'disponible'}">` +
           `• ${esc(texto.charAt(0) + texto.slice(1).toLowerCase())}</span>`;
}

/** Pinta el desglose fiscal en cualquier pantalla que tenga los 3 elementos. */
function pintarDesgloseIgv(ids, total) {
    const d = desglosarIgv(total);
    const baseEl = document.getElementById(ids.base);
    const igvEl = document.getElementById(ids.igv);
    const totalEl = document.getElementById(ids.total);
    const pctEl = document.getElementById(ids.porcentaje);

    if (baseEl) baseEl.innerText = fmtDinero(d.base);
    if (igvEl) igvEl.innerText = fmtDinero(d.igv);
    if (totalEl) totalEl.innerText = fmtDinero(d.total);
    if (pctEl) pctEl.innerText = `IGV ${d.porcentaje.toFixed(0)}%`;
    return d;
}

// =====================================================
// 1. LOGIN & PIN PAD
// =====================================================
let solicitudPin = null;
let focoAntesDelPin = null;
let cierrePinTemporizador = null;

function abrirPinPad(rol) {
    const modal = document.getElementById('pinpad-modal');
    if (!modal) return;
    clearTimeout(cierrePinTemporizador);
    focoAntesDelPin = document.activeElement;
    rolSeleccionado = rol;
    pinActual = '';
    document.getElementById('pin-input').value = '';
    document.getElementById('pinpad-title').textContent = rol;
    limpiarErrorPin();
    modal.classList.remove('is-closing');
    modal.inert = false;
    modal.style.display = 'flex';
    modal.setAttribute('aria-hidden', 'false');
    document.body.classList.add('pin-is-open');
    document.querySelector('.access-shell').inert = true;
    // El teclado de la pantalla ya permite escribir sin abrir otro teclado móvil.
    modal.querySelector('.pin-key').focus({ preventScroll: true });
}

function cerrarPinPad() {
    const modal = document.getElementById('pinpad-modal');
    if (!modal || modal.getAttribute('aria-hidden') === 'true') return;
    if (solicitudPin) {
        solicitudPin.abort();
        solicitudPin = null;
        estadoCargaPin(false);
    }
    modal.setAttribute('aria-hidden', 'true');
    modal.inert = true;
    modal.classList.add('is-closing');
    document.body.classList.remove('pin-is-open');
    document.querySelector('.access-shell').inert = false;
    focoAntesDelPin?.focus({ preventScroll: true });
    const duracion = window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 0 : 160;
    cierrePinTemporizador = setTimeout(() => {
        modal.style.display = 'none';
        modal.classList.remove('is-closing');
        pinActual = '';
        document.getElementById('pin-input').value = '';
    }, duracion);
}

function limpiarErrorPin() {
    const error = document.getElementById('pinpad-error');
    if (error) { error.hidden = true; error.textContent = ''; }
    document.getElementById('pin-input')?.removeAttribute('aria-invalid');
}

function mostrarErrorPin(mensaje) {
    const error = document.getElementById('pinpad-error');
    error.textContent = mensaje;
    error.hidden = false;
    document.getElementById('pin-input').setAttribute('aria-invalid', 'true');
    if (!window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
        document.querySelector('.pin-display-wrapper').animate(
            [{ transform: 'translateX(0)' }, { transform: 'translateX(-5px)' },
             { transform: 'translateX(5px)' }, { transform: 'translateX(0)' }],
            { duration: 240, easing: 'ease-out' });
    }
}

function actualizarPin(valor) {
    if (solicitudPin) return;
    pinActual = valor.replace(/[^0-9]/g, '').slice(0, 10);
    const input = document.getElementById('pin-input');
    input.value = pinActual;
    limpiarErrorPin();
    if (!window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
        input.getAnimations().forEach(animacion => animacion.cancel());
        input.animate([{ transform: 'scale(0.985)' }, { transform: 'scale(1)' }],
            { duration: 160, easing: 'cubic-bezier(.2,.8,.2,1)' });
    }
}

function agregarNumero(num) { actualizarPin(pinActual + num); }
function borrarNumero() { actualizarPin(pinActual.slice(0, -1)); }

function estadoCargaPin(cargando) {
    document.getElementById('pin-form').setAttribute('aria-busy', String(cargando));
    document.querySelectorAll('.pin-key, #pin-input').forEach(control => control.disabled = cargando);
    document.getElementById('pin-confirm').textContent = cargando ? 'Entrando…' : 'Entrar';
}

async function validarPin() {
    if (solicitudPin) return;
    if (!pinActual) {
        mostrarErrorPin('Ingresa tu PIN para continuar.');
        return;
    }
    limpiarErrorPin();
    const controlador = new AbortController();
    solicitudPin = controlador;
    estadoCargaPin(true);
    const tiempoLimite = setTimeout(() => controlador.abort(), 15000);
    try {
        const response = await fetch(`${API_URL}/login`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json', 'X-Pinggy-No-Screen': 'true' },
            body: JSON.stringify({ rol: rolSeleccionado, pin: pinActual }),
            signal: controlador.signal
        });
        const data = await response.json();
        if (response.ok && data.success) {
            guardarSesion(data);
            document.getElementById('pin-confirm').textContent = 'Listo';
            navegarConTransicion(data.user.rol_nombre === 'ADMIN' ? 'inventario.html' : 'cajero.html');
        } else {
            pinActual = '';
            document.getElementById('pin-input').value = '';
            mostrarErrorPin(data.message || 'PIN o rol incorrecto. Intenta nuevamente.');
        }
    } catch (err) {
        if (solicitudPin !== controlador) return; // Se cerró el diálogo.
        mostrarErrorPin(err.name === 'AbortError'
            ? 'El servidor tarda en responder. Vuelve a intentar.'
            : 'No pudimos conectar. Revisa tu conexión e intenta nuevamente.');
    } finally {
        clearTimeout(tiempoLimite);
        if (solicitudPin === controlador) {
            solicitudPin = null;
            estadoCargaPin(false);
        }
    }
}

// =====================================================
// 2. MÓDULO DE INVENTARIO (ADMIN)
// =====================================================
async function cargarCategoriasInventario() {
    const filtro = document.getElementById('filtro-categoria');
    const selector = document.getElementById('prod-cat');
    if (!filtro || !selector) return;

    // Las opciones del HTML sirven de respaldo si el catálogo no responde.
    categoriasInventario = new Map([...selector.options].map(opcion => [opcion.value, opcion.textContent]));
    try {
        const response = await apiFetch('/categorias');
        if (!response.ok) return;
        const categorias = await response.json();
        if (!Array.isArray(categorias) || categorias.length === 0) return;
        actualizarCategoriasInventario(categorias);
    } catch (err) {
        console.error('Error al cargar categorías:', err);
    }
}

function actualizarCategoriasInventario(categorias) {
    const filtro = document.getElementById('filtro-categoria');
    const selector = document.getElementById('prod-cat');
    categoriasCatalogo = categorias;
    categoriasToppingIds = new Set(categorias.filter(c => c.esTopping).map(c => Number(c.idCategoria)));
    categoriasSaborIds = new Set(categorias.filter(c => c.esSabor).map(c => Number(c.idCategoria)));
    categoriasFrutaIds = new Set(categorias.filter(c => c.esFruta).map(c => Number(c.idCategoria)));

    const categoriaFiltrada = filtro.value;
    const categoriaSeleccionada = selector.value;
    categoriasInventario = new Map();
    filtro.replaceChildren(new Option('Todas las categorías', ''));
    selector.replaceChildren();
    categorias.forEach(categoria => {
        const id = String(categoria.idCategoria ?? categoria.id_categoria);
        const nombre = categoria.nombre ?? categoria.nombreCategoria ?? categoria.nombre_categoria;
        if (!nombre || id === 'undefined') return;
        categoriasInventario.set(id, nombre);
        filtro.add(new Option(nombre, id));
        selector.add(new Option(nombre, id));
    });
    filtro.value = categoriaFiltrada;
    selector.value = categoriaSeleccionada;
}

function abrirModalCategoria() {
    document.getElementById('form-categoria').reset();
    document.getElementById('categoria-error').hidden = true;
    document.getElementById('modal-categoria').showModal();
}

function cerrarModalCategoria() {
    if (document.querySelector('#form-categoria button[type="submit"]').disabled) return;
    document.getElementById('modal-categoria').close();
}

async function guardarCategoria(event) {
    event.preventDefault();
    const form = document.getElementById('form-categoria');
    const boton = form.querySelector('button[type="submit"]');
    if (boton.disabled || !form.reportValidity()) return;
    const error = document.getElementById('categoria-error');
    const nombre = document.getElementById('categoria-nombre').value.trim().replace(/\s+/g, ' ');
    if (!nombre) {
        error.textContent = 'Escribe el nombre de la categoría.';
        error.hidden = false;
        document.getElementById('categoria-nombre').focus();
        return;
    }
    error.hidden = true;
    boton.disabled = true;
    boton.querySelector('span').textContent = 'Guardando…';
    form.setAttribute('aria-busy', 'true');
    const dialogo = document.getElementById('modal-categoria');
    const impedirCierre = e => e.preventDefault();
    dialogo.addEventListener('cancel', impedirCierre);
    try {
        const response = await apiFetch('/categorias', {
            method: 'POST', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ nombre, descripcion: document.getElementById('categoria-descripcion').value.trim() })
        });
        const data = await response.json();
        if (!response.ok) throw new Error(data.message || 'No se pudo guardar la categoría. Inténtalo de nuevo.');
        actualizarCategoriasInventario([...categoriasCatalogo, data.categoria]);
        dialogo.close();
        const estado = document.getElementById('categoria-estado');
        estado.textContent = `Categoría ${data.categoria.nombre} creada. Ya puedes seleccionarla al agregar productos, toppings o sabores.`;
        estado.hidden = false;
    } catch (err) {
        error.textContent = err.message || 'No se pudo conectar. Inténtalo de nuevo.';
        error.hidden = false;
    } finally {
        boton.disabled = false;
        boton.querySelector('span').textContent = 'Guardar categoría';
        form.removeAttribute('aria-busy');
        dialogo.removeEventListener('cancel', impedirCierre);
    }
}

async function obtenerProductos() {
    try {
        const response = await apiFetch(`/productos`);
        if (!response.ok) throw new Error('Error al cargar productos');
        productosLista = await response.json();
    } catch (err) {
        console.error('Error al conectar con PostgreSQL:', err);
        productosLista = [];
    }
    filtrarProductos();
}

function renderizarTabla(lista) {
    const tbody = document.getElementById('tabla-productos-body');
    if (!tbody) return;

    tbody.innerHTML = '';
    if (lista.length === 0) {
        tbody.innerHTML = '<tr><td colspan="8" style="text-align:center; padding: 20px; color: var(--text-muted);">No hay productos registrados en PostgreSQL.</td></tr>';
        return;
    }

    lista.forEach(prod => {
        const id = prod.idProducto || prod.id_producto;
        const nombre = prod.nombreProducto || prod.nombre_producto;
        const ref = prod.referencia || '';
        const categoriaId = String(prod.idCategoria ?? prod.id_categoria ?? '');
        const categoria = categoriasInventario.get(categoriaId) || (categoriaId ? `Categoría ${categoriaId}` : 'Sin categoría');
        const precio = prod.precio || 0;
        const stock = prod.stock || 0;
        const estado = prod.estado || 'Disponible';
        const img = prod.urlImagen || prod.url_imagen || 'lastulas.jpg';

        let badgeClass = 'disponible';
        if (estado === 'Stock bajo') badgeClass = 'stock-bajo';
        if (estado === 'Agotado') badgeClass = 'agotado';

        const tr = document.createElement('tr');
        tr.innerHTML = `
            <td><img src="${esc(img)}" class="product-img-thumb" alt="${esc(nombre)}" onerror="this.src='lastulas.jpg'"></td>
            <td><strong>${esc(nombre)}</strong><span class="inventory-mobile-meta">${esc(categoria)} · S/ ${parseFloat(precio).toFixed(2)} · ${esc(stock)} en stock</span></td>
            <td>${esc(categoria)}</td>
            <td>${esc(ref)}</td>
            <td class="inventory-price">S/ ${parseFloat(precio).toFixed(2)}</td>
            <td>${esc(stock)}</td>
            <td><span class="status-badge ${badgeClass}">• ${esc(estado)}</span></td>
            <td>
                <div class="table-actions-btns">
                    <button type="button" class="table-row-action" aria-label="Editar ${esc(nombre)}" title="Editar" onclick="abrirModalEditar(${esc(id)})"><i class="fa-solid fa-pen-to-square" aria-hidden="true"></i></button>
                    <button type="button" class="table-row-action danger" aria-label="Eliminar ${esc(nombre)}" title="Eliminar" onclick="abrirModalEliminar(${esc(id)}, '${esc(nombre).replace(/'/g, '\\u0027')}')"><i class="fa-regular fa-trash-can" aria-hidden="true"></i></button>
                </div>
            </td>
        `;
        tbody.appendChild(tr);
    });
}

function filtrarProductos() {
    const texto = (document.getElementById('filtro-buscar')?.value || '').toLowerCase();
    const cat = document.getElementById('filtro-categoria')?.value || '';
    const estado = document.getElementById('filtro-estado')?.value || '';

    const filtrados = productosLista.filter(p => {
        const nombre = (p.nombreProducto || p.nombre_producto || '').toLowerCase();
        const ref = (p.referencia || '').toLowerCase();
        const categoriaId = String(p.idCategoria ?? p.id_categoria ?? '');
        const est = p.estado || '';

        const coincideTexto = nombre.includes(texto) || ref.includes(texto);
        const coincideCat = cat === '' || categoriaId === cat;
        const coincideEstado = estado === '' || est === estado;

        return coincideTexto && coincideCat && coincideEstado;
    });

    renderizarTabla(filtrados);
}

function abrirModalCrear() {
    document.getElementById('modal-prod-titulo').innerText = 'AGREGAR PRODUCTO';
    document.getElementById('form-producto').reset();
    formularioAdicionalInventario = false;
    document.getElementById('prod-destinos').replaceChildren();
    document.getElementById('prod-error').hidden = true;
    configurarTipoAdicionalInventario();
    document.getElementById('prod-id').value = '';
    imagenActual = 'lastulas.jpg';
    mostrarPreview('lastulas.jpg', 'Por defecto: lastulas.jpg');
    document.getElementById('modal-producto').style.display = 'flex';
    document.querySelector('#modal-producto .modal-form-fields')?.scrollTo({ top: 0 });
}

function abrirModalEditar(id) {
    const p = productosLista.find(prod => (prod.idProducto || prod.id_producto) === id);
    if (!p) return;

    document.getElementById('modal-prod-titulo').innerText = 'EDITAR PRODUCTO';
    document.getElementById('prod-destinos').replaceChildren();
    formularioAdicionalInventario = esAdicional(p);
    document.getElementById('prod-tipo').value = esSabor(p) ? 'sabores' : esFruta(p) ? 'frutas' : 'toppings';
    document.getElementById('prod-gratis').checked = Number(p.precio) === 0;
    document.getElementById('prod-error').hidden = true;
    configurarTipoAdicionalInventario(p.categoriasDestino || []);
    document.getElementById('modal-prod-titulo').textContent = formularioAdicionalInventario ? (esSabor(p) ? 'EDITAR SABOR' : esFruta(p) ? 'EDITAR FRUTA' : 'EDITAR TOPPING') : 'EDITAR PRODUCTO';
    document.getElementById('prod-id').value = p.idProducto || p.id_producto;
    document.getElementById('prod-nombre').value = p.nombreProducto || p.nombre_producto;
    document.getElementById('prod-ref').value = p.referencia;
    document.getElementById('prod-precio').value = p.precio;
    document.getElementById('prod-stock').value = p.stock;
    document.getElementById('prod-cat').value = p.idCategoria || p.id_categoria;
    document.getElementById('prod-url-input').value = '';

    imagenActual = p.urlImagen || p.url_imagen || 'lastulas.jpg';
    mostrarPreview(imagenActual, 'Foto cargada');

    document.getElementById('modal-producto').style.display = 'flex';
    document.querySelector('#modal-producto .modal-form-fields')?.scrollTo({ top: 0 });
}

function cerrarModalProducto() {
    document.getElementById('modal-producto').style.display = 'none';
}

function abrirModalCrearAdicional() {
    abrirModalCrear();
    formularioAdicionalInventario = true;
    document.getElementById('prod-tipo').value = 'toppings';
    configurarTipoAdicionalInventario();
}

function configurarTipoAdicionalInventario(destinos = []) {
    // onchange tambien puede pasar un evento; la seleccion se conserva entre tipos.
    if (!Array.isArray(destinos)) destinos = [];
    const elegidos = new Set([...destinos.map(String), ...Array.from(document.querySelectorAll('#prod-destinos input:checked')).map(el => el.value)]);
    const tipo = document.getElementById('prod-tipo').value;
    document.getElementById('prod-tipo-group').hidden = !formularioAdicionalInventario;
    document.getElementById('prod-destinos-group').hidden = !formularioAdicionalInventario;
    document.getElementById('prod-gratis-group').hidden = !formularioAdicionalInventario || tipo === 'sabores';
    document.getElementById('prod-sabor-help').hidden = !formularioAdicionalInventario || tipo !== 'sabores';
    document.getElementById('prod-cat-label').textContent = formularioAdicionalInventario ? 'Catálogo del adicional' : 'Categoría';
    if (formularioAdicionalInventario) document.getElementById('modal-prod-titulo').textContent =
        `${document.getElementById('prod-id').value ? 'EDITAR' : 'AGREGAR'} ${tipo === 'sabores' ? 'SABOR' : tipo === 'frutas' ? 'FRUTA' : 'TOPPING'}`;
    const selector = document.getElementById('prod-cat');
    const anterior = selector.value;
    selector.replaceChildren();
    categoriasCatalogo.filter(c => formularioAdicionalInventario
        ? (tipo === 'sabores' ? c.esSabor : tipo === 'frutas' ? c.esFruta : c.esTopping) : (!c.esTopping && !c.esSabor && !c.esFruta))
        .forEach(c => selector.add(new Option(c.nombre, c.idCategoria)));
    const sinCatalogo = selector.options.length === 0;
    selector.required = true;
    selector.disabled = sinCatalogo;
    const ayuda = document.getElementById('prod-catalogo-estado');
    if (sinCatalogo) {
        const opcion = new Option('Catálogo no disponible', '');
        opcion.disabled = true;
        selector.add(opcion);
        const servidorAntiguo = tipo === 'frutas' && categoriasCatalogo.length > 0
            && categoriasCatalogo.every(c => !Object.hasOwn(c, 'esFruta'));
        ayuda.textContent = servidorAntiguo
            ? 'El servidor todavía usa una versión sin frutas. Reinicia el backend actualizado y recarga Inventario.'
            : 'No se encontró el catálogo de este adicional. Ejecuta la migración 07 en la base usada por la aplicación, reinicia el backend y recarga Inventario.';
    } else {
        if (Array.from(selector.options).some(o => o.value === anterior)) selector.value = anterior;
        ayuda.textContent = '';
    }
    ayuda.hidden = !sinCatalogo;
    document.querySelector('#form-producto button[type="submit"]').disabled = sinCatalogo;
    const opciones = document.getElementById('prod-destinos');
    opciones.replaceChildren();
    categoriasCatalogo.filter(c => !c.esTopping && !c.esSabor && !c.esFruta).forEach(c => {
        const label = document.createElement('label');
        const check = document.createElement('input');
        check.type = 'checkbox'; check.value = c.idCategoria; check.checked = elegidos.has(String(c.idCategoria));
        label.append(check, document.createTextNode(c.nombre)); opciones.append(label);
    });
    actualizarPrecioAdicionalInventario();
}

function actualizarPrecioAdicionalInventario() {
    const gratis = formularioAdicionalInventario &&
        (document.getElementById('prod-tipo').value === 'sabores' || document.getElementById('prod-gratis').checked);
    const precio = document.getElementById('prod-precio');
    precio.readOnly = gratis;
    if (gratis) precio.value = '0.00';
}

async function guardarProducto(e) {
    e.preventDefault();
    const id = document.getElementById('prod-id').value;
    const error = document.getElementById('prod-error');
    error.hidden = true;
    if (!document.getElementById('prod-cat').value || document.getElementById('prod-cat').disabled) {
        error.textContent = 'Selecciona un catálogo válido antes de guardar el producto.';
        error.hidden = false;
        return;
    }
    const destinos = formularioAdicionalInventario
        ? Array.from(document.querySelectorAll('#prod-destinos input:checked')).map(el => Number(el.value)) : [];
    if (formularioAdicionalInventario && !destinos.length) {
        error.textContent = 'Selecciona al menos una categoría donde se podrá elegir este adicional.';
        error.hidden = false;
        document.querySelector('#prod-destinos input')?.focus();
        return;
    }
    const payload = {
        nombreProducto: document.getElementById('prod-nombre').value.trim(),
        referencia: document.getElementById('prod-ref').value.trim(),
        precio: Number(document.getElementById('prod-precio').value),
        stock: Number(document.getElementById('prod-stock').value),
        idCategoria: Number(document.getElementById('prod-cat').value),
        categoriasDestino: destinos,
        urlImagen: imagenActual
    };
    const boton = document.querySelector('#form-producto button[type="submit"]');
    if (boton.disabled) return;
    boton.disabled = true;
    try {
        const res = await apiFetch(id ? `/productos/${id}` : '/productos', {
            method: id ? 'PUT' : 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload)
        });
        const data = await res.json();
        if (!res.ok) throw new Error(data.message || 'No se pudo guardar. Revisa los datos e inténtalo de nuevo.');
        cerrarModalProducto();
        await obtenerProductos();
    } catch (err) {
        error.textContent = err.message || 'No se pudo conectar. Inténtalo de nuevo.';
        error.hidden = false;
        error.scrollIntoView({ block: 'nearest' });
    } finally { boton.disabled = false; }
}

function cargarImagenArchivo(archivo) {
    if (archivo && archivo.type.startsWith('image/')) {
        const reader = new FileReader();
        reader.onload = function(e) {
            imagenActual = e.target.result;
            mostrarPreview(imagenActual, archivo.name);
        };
        reader.readAsDataURL(archivo);
    }
}

function cargarImagenUrl(url) {
    if (url.trim() !== '') {
        imagenActual = url.trim();
        mostrarPreview(imagenActual, 'Foto desde URL');
    }
}

function quitarFoto() {
    imagenActual = 'lastulas.jpg';
    const urlInput = document.getElementById('prod-url-input');
    const fileInput = document.getElementById('file-input');
    if (urlInput) urlInput.value = '';
    if (fileInput) fileInput.value = '';
    mostrarPreview('lastulas.jpg', 'Sin foto personalizada');
}

function mostrarPreview(src, texto) {
    const previewBox = document.getElementById('preview-container');
    const previewImg = document.getElementById('img-preview');
    const previewTxt = document.getElementById('preview-text');
    if (previewBox && previewImg) {
        previewImg.src = src;
        if (previewTxt) previewTxt.innerText = texto;
        previewBox.style.display = 'flex';
    }
}

function abrirModalEliminar(id, nombre) {
    idProductoAEliminar = id;
    document.getElementById('delete-modal-msg').innerHTML = `¿Seguro que deseas eliminar <strong>${esc(nombre)}</strong>?`;
    document.getElementById('btn-confirmar-delete').onclick = ejecutarEliminarProducto;
    document.getElementById('modal-eliminar').style.display = 'flex';
}

function cerrarModalEliminar() {
    document.getElementById('modal-eliminar').style.display = 'none';
    idProductoAEliminar = null;
}
async function ejecutarEliminarProducto() {
    if (!idProductoAEliminar) return;
    try {
        const res = await apiFetch(`/productos/${idProductoAEliminar}`, { method: 'DELETE' });
        if (!res.ok) {
            mostrarAvisoSistema('No se pudo eliminar el producto.');
            return;
        }
    } catch (err) {
        console.error('Error al eliminar:', err);
        errorControlado(err, 'Error de conexión al eliminar el producto.');
        return;
    }
    cerrarModalEliminar();
    obtenerProductos();
}

// =====================================================
// 3. MÓDULO CAJERO (POS)
// =====================================================
async function cargarPOS() {
    const nombreCajero = sessionStorage.getItem('usuario_nombre');
    const labelCajero = document.getElementById('nombre-cajero');
    if (labelCajero && nombreCajero) {
        labelCajero.innerText = nombreCajero;
    }

    await cargarConfigNegocio();

    try {
        // /productos sin incluirInactivos: GET /api/productos ya devuelve solo
        // los activos, asi que un producto dado de baja desaparece de la carta
        // sin tocar esta pantalla (punto 9).
        const res = await apiFetch(`/productos`);
        if (!res.ok) throw new Error('Error al cargar');
        productosLista = await res.json();
    } catch (err) {
        productosLista = [];
    }

    try { await cargarCategoriasAdicionalesPOS(); } catch (err) {
        document.getElementById('pos-grid-productos').innerHTML = '<p role="alert">No se pudo cargar el catálogo. Recarga la página para continuar.</p>';
        actualizarVistaPedidoPOS();
        return;
    }
    sincronizarCarritoConCatalogo();
    await cargarCategoriasCarta();
    renderizarCategoriasPOS();
    renderizarPOS();
    actualizarInfoSeleccionPOS();
    actualizarVistaPedidoPOS();
    await actualizarEstadoCajaPOS();
}

/** Clasifica los catalogos de toppings y sabores; el destino viene en cada adicional. */
async function cargarCategoriasAdicionalesPOS() {
    const res = await apiFetch('/categorias');
    if (!res.ok) throw new Error('No se pudieron cargar las categorias de adicionales.');
    categoriasCatalogo = await res.json();
    categoriasToppingIds = new Set(categoriasCatalogo.filter(c => c.esTopping).map(c => Number(c.idCategoria)));
    categoriasSaborIds = new Set(categoriasCatalogo.filter(c => c.esSabor).map(c => Number(c.idCategoria)));
    categoriasFrutaIds = new Set(categoriasCatalogo.filter(c => c.esFruta).map(c => Number(c.idCategoria)));
}

function esSabor(p) {
    return categoriasSaborIds.has(Number(p.idCategoria ?? p.id_categoria));
}

function esFruta(p) { return categoriasFrutaIds.has(Number(p.idCategoria ?? p.id_categoria)); }
function esAdicional(p) { return esTopping(p) || esSabor(p) || esFruta(p); }

/** Un producto es topping si su categoria esta marcada en la base. */
function esTopping(p) {
    return categoriasToppingIds.has(Number(p.idCategoria || p.id_categoria));
}

/** Categorias de la carta, excluyendo las de toppings. */
function categoriasDeCarta() {
    const ids = new Set();
    productosLista
        .filter(p => !esAdicional(p))
        .forEach(p => ids.add(Number(p.idCategoria || p.id_categoria)));
    return Array.from(ids);
}

/**
 * Arma los botones de categoria a partir de los datos, no de ids fijos.
 * Estaba hardcodeado a la categoria 5 para los toppings y el resto de botones
 * venian del HTML; con la 7 (TOPPING) tambien marcada, un topping se
 * mostraba dos veces.
 */
function renderizarCategoriasPOS() {
    const cont = document.getElementById('pos-categorias');
    if (!cont) return;
    const cats = Array.isArray(categoriasCartaCache) ? [...categoriasCartaCache] : [];
    const nombreOrden = c => (c.nombre || '').trim().toUpperCase().replace(/^CREPE$/, 'CREPES');
    const ordenDespuesDulchitas = ['BEBIDAS', 'HELADOS', 'PROMOS', 'BANDERILLAS', 'CREPES'];
    const indiceDulchitas = cats.findIndex(c => ['DULCHITAS', 'DUCHITAS'].includes(nombreOrden(c)));
    if (indiceDulchitas !== -1) {
        const categoriasAdelantadas = cats.filter(c => ordenDespuesDulchitas.includes(nombreOrden(c)))
            .sort((a, b) => ordenDespuesDulchitas.indexOf(nombreOrden(a)) - ordenDespuesDulchitas.indexOf(nombreOrden(b)));
        const restantes = cats.filter(c => !ordenDespuesDulchitas.includes(nombreOrden(c)));
        const destino = restantes.findIndex(c => ['DULCHITAS', 'DUCHITAS'].includes(nombreOrden(c))) + 1;
        restantes.splice(destino, 0, ...categoriasAdelantadas);
        cats.splice(0, cats.length, ...restantes);
    }
    cont.innerHTML = '';

    const boton = (id, nombre, activo) => {
        const b = document.createElement('button');
        b.type = 'button';
        b.className = 'btn-cat-item' + (activo ? ' active' : '');
        b.setAttribute('aria-pressed', String(activo));
        b.innerHTML = `${esc(nombre)}`;
        b.onclick = (ev) => filtrarPOS(id, ev.currentTarget);
        return b;
    };

    cont.appendChild(boton(0, 'Todos', true));
    cats.forEach(c => cont.appendChild(boton(c.id, c.nombre, false)));
}

/**
 * Punto 11: el POS avisa si el cajero todavia no abrio su turno de caja.
 *
 * Cobrar sin turno no es un error: la venta se guarda igual. Pero esa venta
 * queda fuera de todo arqueo, asi que el cajero tiene que enterarse antes, no
 * al cierre del dia cuando ya no se puede recuperar el dato.
 */
async function actualizarEstadoCajaPOS() {
    const cont = document.getElementById('pos-caja-actual');
    if (!cont) return;
    try {
        const res = await apiFetch('/caja/actual');
        if (!res.ok) return;
        const caja = await res.json();

        if (!caja.abierto) {
            cont.innerHTML = `
                <div style="padding:8px; border:1px dashed #DC2626; border-radius:8px; color:#DC2626; font-weight:700;">
                    <i class="fa-solid fa-triangle-exclamation"></i> Caja cerrada
                    <div style="font-weight:400; margin-top:4px;">El administrador debe abrir la caja general para comenzar a vender.</div>
                    <a href="cierre_caja.html" style="color:inherit; text-decoration:underline;">Ir a caja &rarr;</a>
                </div>`;
            return;
        }
        cont.innerHTML = `
            <div style="padding:8px; border:1px solid #059669; border-radius:8px; color:#059669; font-weight:700;">
                <i class="fa-solid fa-circle-check"></i> Turno abierto
                <div style="font-weight:400; margin-top:4px;">
                    Base: ${fmtDinero(caja.montoInicial)}<br>
                    Ventas: ${caja.totalBoletas} &middot; ${fmtDinero(caja.totalVentas)}<br>
                    Efectivo en gaveta: ${fmtDinero(caja.efectivoEsperado)}
                </div>
                <a href="cierre_caja.html" style="color:inherit; text-decoration:underline;">Ver caja &rarr;</a>
            </div>`;
    } catch (err) {
        cont.innerHTML = '';
    }
}

let categoriasCartaCache = null;

/** Carga de /api/categorias, una sola vez. */
async function cargarCategoriasCarta() {
    if (categoriasCartaCache) return categoriasCartaCache;
    try {
        const res = await apiFetch(`/categorias`);
        if (!res.ok) return null;
        const todas = await res.json();
        categoriasCartaCache = todas
            .filter(c => !c.esTopping && !c.esSabor && !c.esFruta)
            .map(c => ({ id: c.idCategoria, nombre: c.nombre || c.nombre_categoria || c.nombreCategoria }));
    } catch (err) {
        categoriasCartaCache = null;
    }
    return categoriasCartaCache;
}

// Un producto está disponible para el cajero solo si tiene stock y no está marcado "Agotado".
function estaDisponibleParaCajero(p) {
    const stock = p.stock !== undefined && p.stock !== null ? Number(p.stock) : 0;
    const estado = (p.estado || '').toLowerCase();
    return stock > 0 && estado !== 'agotado';
}

function renderizarPOS() {
    const container = document.getElementById('pos-grid-productos');
    if (!container) return;

    const textoBusqueda = (document.getElementById('input-buscar-pos')?.value || '').trim().toLocaleLowerCase('es-PE');
    const prodsPrincipales = productosLista
        .filter(p => !esAdicional(p))
        .filter(estaDisponibleParaCajero)
        .filter(p => categoriaPOSActual === 0 || Number(p.idCategoria || p.id_categoria) === categoriaPOSActual)
        .filter(p => {
            if (!textoBusqueda) return true;
            const nombre = (p.nombreProducto || p.nombre_producto || '').toLocaleLowerCase('es-PE');
            const referencia = (p.referencia || '').toLocaleLowerCase('es-PE');
            return nombre.includes(textoBusqueda) || referencia.includes(textoBusqueda);
        });
    const resumen = document.getElementById('pos-results-summary');
    if (resumen) {
        const total = prodsPrincipales.length;
        resumen.textContent = textoBusqueda
            ? `${total} ${total === 1 ? 'resultado' : 'resultados'}`
            : `${total} ${total === 1 ? 'producto disponible' : 'productos disponibles'}`;
    }

    if (prodsPrincipales.length === 0) {
        container.innerHTML = `<div class="pos-empty-state"><i class="fa-solid fa-magnifying-glass" aria-hidden="true"></i><strong>No encontramos productos</strong><span>${textoBusqueda ? 'Prueba con otro nombre o referencia.' : 'No hay productos disponibles en esta categoría.'}</span></div>`;
        actualizarVolverArribaPOS();
        return;
    }

    const tarjetasActuales = new Map(Array.from(container.querySelectorAll('.pos-product-card'))
        .map(card => [card.dataset.productId, card]));
    const idsVisibles = new Set(prodsPrincipales.map(idProductoPOS));
    Array.from(container.children).forEach(card => {
        if (!idsVisibles.has(card.dataset.productId)) card.remove();
    });

    prodsPrincipales.forEach((p, indice) => {
        const nombre = p.nombreProducto || p.nombre_producto;
        const precio = parseFloat(p.precio || 0);
        const img = p.urlImagen || p.url_imagen || 'lastulas.jpg';
        const id = idProductoPOS(p);
        const isInCart = posCarrito.some(item => idProductoPOS(item.producto) === id);

        const card = tarjetasActuales.get(id) || document.createElement('button');
        card.type = 'button';
        card.className = `pos-product-card ${isInCart ? 'in-cart' : ''}`;
        card.dataset.productId = id;
        card.setAttribute('aria-pressed', String(isInCart));
        card.setAttribute('aria-label', `${nombre}, ${fmtDinero(precio)}. ${isInCart ? 'En el pedido. Toca para quitar.' : 'Toca para agregar.'}`);
        card.onclick = () => seleccionarProductoPOS(p, card);
        if (!tarjetasActuales.has(id)) card.innerHTML = `
            <span class="check-badge"><i class="fa-solid fa-check" aria-hidden="true"></i></span>
            <img src="${esc(img)}" alt="${esc(nombre)}" loading="lazy" decoding="async" onerror="this.src='lastulas.jpg'">
            <span class="prod-name">${esc(nombre)}</span>
            <span class="prod-desc-sub">${esc(p.referencia || 'Disponible')}</span>
            <span class="prod-price-tag">S/ ${precio.toFixed(2)}</span>
            <span class="prod-card-action"><i class="fa-solid ${isInCart ? 'fa-minus' : 'fa-plus'}" aria-hidden="true"></i>${isInCart ? 'Quitar del pedido' : 'Agregar al pedido'}</span>
        `;
        card.querySelector('.prod-name').textContent = nombre;
        card.querySelector('.prod-desc-sub').textContent = p.referencia || 'Disponible';
        card.querySelector('.prod-price-tag').textContent = `S/ ${precio.toFixed(2)}`;
        card.querySelector('.prod-card-action').innerHTML = `<i class="fa-solid ${isInCart ? 'fa-minus' : 'fa-plus'}" aria-hidden="true"></i>${isInCart ? 'Quitar del pedido' : 'Agregar al pedido'}`;
        if (card.dataset.imageSource !== img) {
            // Una selección conserva la misma imagen y su decodificación.
            if (tarjetasActuales.has(id)) card.querySelector('img').src = img;
            card.dataset.imageSource = img;
        }
        card.querySelector('img').alt = nombre;
        if (container.children[indice] !== card) container.insertBefore(card, container.children[indice] || null);
    });
    actualizarVolverArribaPOS();
}

function sincronizarTarjetasToppingsPOS(container, toppings, tipo = 'toppings') {
    const seleccion = tipo === 'sabores' ? saboresSeleccionados : tipo === 'frutas' ? frutasSeleccionadas : toppingsSeleccionados;
    const { cupo, opciones, preparacion } = grupoAdicionalConCupo(productoPosSeleccionado, seleccion, tipo);
    const tarjetasActuales = new Map(Array.from(container.querySelectorAll('.topping-card-pill'))
        .map(pill => [pill.dataset.toppingId, pill]));
    const idsVisibles = new Set(toppings.map(idProductoPOS));
    Array.from(container.children).forEach(pill => {
        if (!idsVisibles.has(pill.dataset.toppingId)) pill.remove();
    });
    toppings.forEach((top, indice) => {
        const id = idProductoPOS(top);
        const nombre = top.nombreProducto || top.nombre_producto;
        const precio = parseFloat(top.precio ?? 0);
        const img = top.urlImagen || top.url_imagen || 'lastulas.jpg';
        const isTopSelected = seleccion.some(t => idProductoPOS(t) === id);

        const pill = tarjetasActuales.get(id) || document.createElement('button');
        pill.type = 'button';
        pill.className = `topping-card-pill ${isTopSelected ? 'active' : ''}`;
        pill.dataset.toppingId = id;
        pill.setAttribute('aria-pressed', String(isTopSelected));
        pill.onclick = () => {
            toggleAdicionalPOS(top, tipo);
            if (container.id === 'grid-modal-todos-toppings') filtrarModalToppings();
        };
        if (!tarjetasActuales.has(id)) pill.innerHTML = `
            <i class="fa-solid fa-check topping-check" aria-hidden="true"></i>
            <img src="${esc(img)}" width="40" height="40" loading="lazy" decoding="async" onerror="this.src='lastulas.jpg'" alt="">
            <span class="topping-pill-copy">
                <span>${esc(nombre)}</span>
                <small style="display:block; color:var(--primary-pink); font-size:11px;">+ S/ ${precio.toFixed(2)}</small>
            </span>
        `;
        pill.querySelector('.topping-pill-copy > span').textContent = nombre;
        const posicion = opciones.findIndex(t => idProductoPOS(t) === id);
        const incluido = cupo > 0 && (!preparacion || idProductoPOS(preparacion) !== id)
            && (posicion >= 0 ? posicion < cupo : opciones.length < cupo);
        const porciones = seleccion.filter(t => idProductoPOS(t) === id).length;
        pill.querySelector('.topping-pill-copy > small').textContent = porciones > 1 ? `${porciones} porciones`
            : incluido ? 'Incluido' : precio === 0 ? 'Gratis' : `+ S/ ${precio.toFixed(2)}`;
        if (pill.dataset.imageSource !== img) {
            if (tarjetasActuales.has(id)) pill.querySelector('img').src = img;
            pill.dataset.imageSource = img;
        }
        pill.querySelector('img').alt = '';
        if (container.children[indice] !== pill) container.insertBefore(pill, container.children[indice] || null);
    });
}

/** Solo opciones con stock asignadas expresamente a la categoria del producto. */
function adicionalesDisponiblesPara(producto, tipo) {
    if (!producto) return [];
    const categoria = Number(producto.idCategoria ?? producto.id_categoria);
    return productosLista.filter(p => tipo === 'sabores' ? esSabor(p) : tipo === 'frutas' ? esFruta(p) : esTopping(p))
        .filter(estaDisponibleParaCajero)
        .filter(p => (p.categoriasDestino || []).some(id => Number(id) === categoria));
}

function toppingsDisponiblesPara(producto) { return adicionalesDisponiblesPara(producto, 'toppings'); }
function frutasDisponiblesPara(producto) { return adicionalesDisponiblesPara(producto, 'frutas'); }
function saboresDisponiblesPara(producto) { return adicionalesDisponiblesPara(producto, 'sabores'); }

function filtrarPOS(catId, btn) {
    document.querySelectorAll('.btn-cat-item').forEach(b => {
        b.classList.toggle('active', b === btn);
        b.setAttribute('aria-pressed', String(b === btn));
    });
    categoriaPOSActual = Number(catId) || 0;
    renderizarPOS();
    const reducirMovimiento = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    btn?.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: reducirMovimiento ? 'auto' : 'smooth' });
    const rejilla = document.getElementById('pos-grid-productos');
    if (rejilla && !reducirMovimiento) {
        rejilla.getAnimations().forEach(animacion => animacion.cancel());
        rejilla.animate([{ opacity: 0.3, transform: 'translateY(10px)' },
            { opacity: 1, transform: 'translateY(0)' }],
            { duration: 220, easing: 'cubic-bezier(.2,.8,.2,1)' });
    }
}

function seleccionarItemPOS(item) {
    itemPosSeleccionado = item;
    productoPosSeleccionado = item?.producto || null;
    toppingsSeleccionados = [...(item?.toppings || [])];
    saboresSeleccionados = [...(item?.sabores || [])];
    frutasSeleccionadas = [...(item?.frutas || [])];
}

function seleccionarProductoPOS(p, origen) {
    const id = idProductoPOS(p);
    const indiceExistente = posCarrito.findIndex(item => idProductoPOS(item.producto) === id);

    if (indiceExistente !== -1) {
        const [eliminado] = posCarrito.splice(indiceExistente, 1);
        if (itemPosSeleccionado === eliminado) seleccionarItemPOS(null);
    } else {
        const nuevo = {
            idUnico: Date.now(),
            producto: p,
            cantidad: 1,
            toppings: [],
            sabores: [],
            frutas: []
        };
        posCarrito.push(nuevo);
        seleccionarItemPOS(nuevo);
    }

    renderizarPOS();
    actualizarInfoSeleccionPOS();
    actualizarVistaPedidoPOS();

    if (indiceExistente === -1) {
        if (toppingsDisponiblesPara(p).length || saboresDisponiblesPara(p).length || frutasDisponiblesPara(p).length) abrirModalToppings(origen);
    }
}

function esPreparacionConTresToppings(adicional) {
    const nombre = String(adicional.nombreProducto || adicional.nombre_producto || '').normalize('NFD')
        .replace(/[\u0300-\u036f]|\s/g, '').toLowerCase();
    return nombre === 'preparacion' || nombre === 'preparacion+3toppings';
}

function grupoAdicionalConCupo(producto, seleccion, tipo) {
    const opciones = seleccion || [];
    const categoria = categoriasCatalogo.find(c => Number(c.idCategoria) === Number(producto.idCategoria ?? producto.id_categoria));
    if (tipo === 'toppings' && String(categoria?.nombre || '').trim().toUpperCase() === 'RAMEN') {
        const preparacion = opciones.find(esPreparacionConTresToppings);
        return { cupo: preparacion ? 3 : 0, preparacion,
            opciones: preparacion ? opciones.filter(t => idProductoPOS(t) !== idProductoPOS(preparacion)) : opciones };
    }
    const normalizar = nombre => String(nombre || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '')
        .trim().replace(/\s+/g, ' ').toUpperCase();
    if (tipo === 'toppings' && ['MILK TEA', 'MILK FRAPPE', 'FRUIT FRAPPE', 'FRUIT TEA', 'COCTEL BOBA'].includes(normalizar(categoria?.nombre))) {
        const nombre = normalizar(producto.nombreProducto || producto.nombre_producto)
            .replace(/( (PETIT|REGULAR|R|G))?( *\((12|16) *OZ\))?$/, '');
        const excluido = ['MANGO MATCHA LATTE', 'STRAWBERRY MATCHA LATTE', 'OREO FRAPPE', 'STRAWBERRY TARO LATTE'].includes(nombre);
        return { cupo: excluido ? 0 : 1, opciones, preparacion: null };
    }
    return { cupo: Number(tipo === 'frutas' ? producto.frutasIncluidas : tipo === 'toppings' ? producto.toppingsIncluidos : 0) || 0,
        opciones, preparacion: null };
}

function costoGrupoAdicional(producto, seleccion, tipo) {
    const { cupo, opciones, preparacion } = grupoAdicionalConCupo(producto, seleccion, tipo);
    const extras = preparacion
        ? [...seleccion.filter(t => idProductoPOS(t) === idProductoPOS(preparacion)), ...opciones.slice(cupo)]
        : opciones.slice(cupo);
    return extras.reduce((centavos, adicional) =>
        centavos + Math.round(Number(adicional.precio || 0) * 100), 0) / 100;
}
function sincronizarCarritoConCatalogo() {
    const catalogo = new Map(productosLista.map(p => [idProductoPOS(p), p]));
    posCarrito.forEach(item => {
        const actual = catalogo.get(idProductoPOS(item.producto));
        if (actual) item.producto = actual;
        for (const tipo of ['toppings', 'frutas', 'sabores']) {
            item[tipo] = (item[tipo] || []).map(p => catalogo.get(idProductoPOS(p)) || p);
        }
    });
    productoPosSeleccionado = itemPosSeleccionado?.producto || null;
    guardarCarrito();
}
function costoAdicionalesItem(item) {
    return costoGrupoAdicional(item.producto, item.toppings, 'toppings')
        + costoGrupoAdicional(item.producto, item.frutas, 'frutas');
}
function textoCupoAdicional(producto, seleccion, tipo) {
    const { cupo, opciones } = grupoAdicionalConCupo(producto, seleccion, tipo);
    const cantidad = opciones.length;
    const extras = Math.max(0, cantidad - cupo);
    return cupo ? `${Math.min(cantidad, cupo)} de ${cupo} ${tipo} ${tipo === 'frutas' ? 'incluidas' : 'incluidos'} · ${extras} ${extras === 1 ? 'extra' : 'extras'}` : `${cantidad} ${tipo}`;
}

function resumirAdicionalesPOS(seleccion) {
    const grupos = new Map();
    for (const adicional of seleccion || []) {
        const id = idProductoPOS(adicional);
        const grupo = grupos.get(id) || { nombre: adicional.nombreProducto || adicional.nombre_producto, cantidad: 0 };
        grupo.cantidad++;
        grupos.set(id, grupo);
    }
    return Array.from(grupos.values(), grupo => grupo.cantidad > 1 ? `x${grupo.cantidad} ${grupo.nombre}` : grupo.nombre).join(', ');
}

function toggleToppingPOS(top) { toggleAdicionalPOS(top, 'toppings'); }

function toggleAdicionalPOS(adicional, tipo) {
    if (!productoPosSeleccionado) return;
    const item = itemPosSeleccionado;
    if (!posCarrito.includes(item)) return;
    const seleccion = tipo === 'sabores' ? saboresSeleccionados : tipo === 'frutas' ? frutasSeleccionadas : toppingsSeleccionados;
    const index = seleccion.findIndex(t => idProductoPOS(t) === idProductoPOS(adicional));
    if (index >= 0) {
        for (let i = seleccion.length - 1; i >= 0; i--) {
            if (idProductoPOS(seleccion[i]) === idProductoPOS(adicional)) seleccion.splice(i, 1);
        }
    }
    else {
        if (!adicionalesDisponiblesPara(productoPosSeleccionado, tipo).some(t => idProductoPOS(t) === idProductoPOS(adicional))) return;
        if (seleccion.length >= 25) return;
        seleccion.push(adicional);
    }
    item[tipo] = [...seleccion];
    actualizarInfoSeleccionPOS();
    actualizarVistaPedidoPOS();
    actualizarResumenModalToppingsPOS();
}

function actualizarInfoSeleccionPOS() {
    const infoText = document.getElementById('texto-seleccion-info');
    if (!infoText) return;

    if (productoPosSeleccionado) {
        const pNombre = esc(productoPosSeleccionado.nombreProducto || productoPosSeleccionado.nombre_producto);
        const sabores = saboresSeleccionados.map(t => esc(t.nombreProducto || t.nombre_producto)).join(', ');
        const topNombres = esc(resumirAdicionalesPOS(toppingsSeleccionados));
        infoText.innerHTML = `Pedido de <strong>${pNombre}</strong> ${topNombres ? `con <strong>${topNombres}</strong>` : '(sin toppings)'}${sabores ? ` · Sabores: <strong>${sabores}</strong>` : ''}. Usa + para agregar otra unidad sin extras y − para quitar una unidad.`;
    } else {
        infoText.textContent = 'Toca un producto para agregarlo. Usa + en el pedido para agregar otra unidad sin extras y − para quitar una unidad.';
    }
}

function actualizarVistaPedidoPOS() {
    guardarCarrito();
    const container = document.getElementById('pos-order-items');
    const btnTotalEl = document.getElementById('pos-btn-total');
    const cantHeaderEl = document.getElementById('contador-items-header');

    if (!container) return;
    container.innerHTML = '';

    let totalGeneral = 0;
    let cantidadTotalProds = 0;

    if (posCarrito.length === 0) {
        container.innerHTML = '<p style="text-align: center; color: var(--text-muted); font-size: 0.85rem; margin-top: 50px;">No hay productos en el pedido.</p>';
    }

    posCarrito.forEach((item, idx) => {
        const p = item.producto;
        const nombre = p.nombreProducto || p.nombre_producto;
        const precioBase = parseFloat(p.precio || 0);
        const totalToppings = costoAdicionalesItem(item);
        const precioItemTotal = (precioBase + totalToppings) * item.cantidad;
        const permiteExtras = ['toppings', 'frutas', 'sabores'].some(tipo =>
            adicionalesDisponiblesPara(p, tipo).length > 0 || item[tipo]?.length > 0);

        totalGeneral += precioItemTotal;
        cantidadTotalProds += item.cantidad;

        const row = document.createElement('div');
        row.className = 'order-item-row';
        row.tabIndex = -1;
        row.setAttribute('role', 'group');
        row.setAttribute('aria-label', `${nombre}, producto ${idx + 1}`);
        row.innerHTML = `
            <div class="item-main-info">
                <div>
                    <h5>${esc(nombre)}</h5>
                    <small style="color:var(--primary-pink); font-weight:700;">S/ ${precioBase.toFixed(2)}</small>
                    <div class="item-adicional-actions">
                    ${permiteExtras ? `<button type="button" class="btn-edit-toppings" data-item-index="${idx}" aria-label="Editar extras de ${esc(nombre)}" onclick="seleccionarItemParaEditarPOS(${idx}, this)"><i class="fa-solid fa-sliders" aria-hidden="true"></i> Extras</button>
                    <button type="button" class="btn-edit-toppings btn-edit-cantidades" data-item-index="${idx}" aria-label="Editar cantidad de extras de ${esc(nombre)}" onclick="seleccionarItemParaEditarPOS(${idx}, this, 'cantidades')"><i class="fa-solid fa-gear" aria-hidden="true"></i> Cantidad</button>` : ''}
                    </div>
                </div>
                <div class="item-qty-controls">
                    <button type="button" class="btn-qty" aria-label="Quitar una unidad de ${esc(nombre)}" onclick="cambiarCantidadItemPOS(${idx}, -1)">−</button>
                    <strong>${esc(item.cantidad)}</strong>
                    <button type="button" class="btn-qty" aria-label="Agregar una unidad de ${esc(nombre)}" onclick="cambiarCantidadItemPOS(${idx}, 1)">+</button>
                    <strong class="item-line-total">S/&nbsp;${precioItemTotal.toFixed(2)}</strong>
                </div>
            </div>
            ${item.notas ? `<button type="button" class="item-note" aria-label="Editar nota de ${esc(nombre)}" onclick="abrirNotasPOS(${idx})"><i class="fa-regular fa-note-sticky" aria-hidden="true"></i><span><strong>Nota:</strong> ${esc(item.notas)}</span><i class="fa-solid fa-pen" aria-hidden="true"></i></button>` : ''}

            ${item.frutas?.length ? `<div class="item-toppings-subtext"><span>Frutas: ${esc(resumirAdicionalesPOS(item.frutas))}</span><span>+ S/ ${costoGrupoAdicional(p, item.frutas, 'frutas').toFixed(2)}</span></div>` : ''}
            ${item.sabores?.length ? `<div class="item-toppings-subtext"><span>Sabores: ${item.sabores.map(t => esc(t.nombreProducto || t.nombre_producto)).join(', ')}</span><span>Gratis</span></div>` : ''}
            ${item.toppings && item.toppings.length > 0 ? `
                <div class="item-toppings-subtext">
                    <span>Toppings: ${esc(resumirAdicionalesPOS(item.toppings))}</span>
                    <span>+ S/ ${costoGrupoAdicional(p, item.toppings, 'toppings').toFixed(2)}</span>
                </div>
            ` : ''}
        `;
        container.appendChild(row);
    });

    // Desglose fiscal (punto 12). El total final no cambia: el precio de carta
    // ya incluye IGV, lo que se agrega es ver cuanto es base y cuanto impuesto.
    pintarDesgloseIgv(
        { base: 'pos-subtotal', igv: 'pos-igv', total: 'pos-total', porcentaje: 'pos-igv-pct' },
        totalGeneral
    );

    if (btnTotalEl) btnTotalEl.innerHTML = `${fmtDinero(totalGeneral)} <i class="fa-solid fa-arrow-right"></i>`;
    if (cantHeaderEl) cantHeaderEl.innerText = `Tu Pedido (${cantidadTotalProds})`;
    const mobileCount = document.getElementById('pos-mobile-cart-count');
    const mobileLabel = document.getElementById('pos-mobile-cart-label');
    const mobileTotal = document.getElementById('pos-mobile-cart-total');
    if (mobileCount) mobileCount.textContent = String(cantidadTotalProds);
    if (mobileLabel) mobileLabel.textContent = `${cantidadTotalProds} ${cantidadTotalProds === 1 ? 'producto' : 'productos'}`;
    if (mobileTotal) mobileTotal.textContent = fmtDinero(totalGeneral);
    const botonNota = document.getElementById('pos-btn-nota');
    if (botonNota) botonNota.disabled = posCarrito.length === 0;
    const cuentaNotas = document.getElementById('pos-nota-count');
    if (cuentaNotas) {
        const cantidad = posCarrito.filter(item => item.notas?.trim()).length;
        cuentaNotas.hidden = cantidad === 0;
        cuentaNotas.textContent = String(cantidad);
    }
}

let borradorNotasPOS = [];

function abrirNotasPOS(indice = 0) {
    if (!posCarrito.length) return;
    const modal = document.getElementById('pos-notas-modal');
    if (!modal || modal.open) return;
    borradorNotasPOS = posCarrito.map(item => ({ item, texto: item.notas || '' }));
    const selector = document.getElementById('pos-notas-producto');
    selector.replaceChildren();
    borradorNotasPOS.forEach(({ item }, index) => {
        selector.add(new Option(`${index + 1}. ${item.cantidad} × ${item.producto.nombreProducto || item.producto.nombre_producto}`, index));
    });
    selector.value = String(Math.max(0, Math.min(indice, borradorNotasPOS.length - 1)));
    alternarSelectorNotaPOS(false);
    cambiarProductoNotaPOS();
    document.body.classList.add('notas-open');
    modal.showModal();
    document.getElementById('pos-notas-title').focus({ preventScroll: true });
}

function cambiarProductoNotaPOS() {
    const selector = document.getElementById('pos-notas-producto');
    document.getElementById('pos-notas-producto-value').textContent = selector.selectedOptions[0]?.textContent || '';
    const borrador = borradorNotasPOS[Number(selector.value)];
    document.getElementById('pos-notas-texto').value = borrador?.texto || '';
    actualizarBorradorNotaPOS();
}

function actualizarBorradorNotaPOS() {
    const texto = document.getElementById('pos-notas-texto').value;
    const borrador = borradorNotasPOS[Number(document.getElementById('pos-notas-producto').value)];
    if (borrador) borrador.texto = texto;
    document.getElementById('pos-notas-limit').textContent = `${texto.length}/300`;
}

function guardarNotasPOS(event) {
    event.preventDefault();
    actualizarBorradorNotaPOS();
    if (borradorNotasPOS.some(borrador => borrador.texto.length > 300)) return;
    borradorNotasPOS.forEach(({ item, texto }) => {
        if (posCarrito.includes(item)) item.notas = texto.trim();
    });
    actualizarVistaPedidoPOS();
    cerrarNotasPOS();
    const estado = document.getElementById('pos-live-status');
    if (estado) estado.textContent = 'Notas del pedido guardadas.';
}

function cerrarNotasPOS() { document.getElementById('pos-notas-modal')?.close(); }

function alternarSelectorNotaPOS(abrir, enfocarOpcion = false) {
    const trigger = document.getElementById('pos-notas-producto-trigger');
    const panel = document.getElementById('pos-notas-producto-panel');
    const expandir = abrir === undefined ? panel.hidden : abrir;
    panel.hidden = !expandir;
    trigger.setAttribute('aria-expanded', String(expandir));
    if (expandir) {
        renderizarSelectorNotaPOS();
        if (enfocarOpcion) (panel.querySelector('[aria-selected="true"]') || panel.querySelector('[role="option"]'))?.focus();
    }
}

function renderizarSelectorNotaPOS() {
    const selector = document.getElementById('pos-notas-producto');
    const lista = document.getElementById('pos-notas-producto-list');
    lista.replaceChildren();
    Array.from(selector.options).forEach(opcion => {
        const boton = document.createElement('button');
        boton.type = 'button';
        boton.className = 'config-picker-option';
        boton.setAttribute('role', 'option');
        boton.setAttribute('aria-selected', String(selector.value === opcion.value));
        boton.tabIndex = -1;
        boton.innerHTML = `<span>${esc(opcion.textContent)}</span><i class="${selector.value === opcion.value ? 'fa-solid fa-check' : ''}" aria-hidden="true"></i>`;
        boton.onclick = () => {
            selector.value = opcion.value;
            cambiarProductoNotaPOS();
            alternarSelectorNotaPOS(false);
            document.getElementById('pos-notas-producto-trigger').focus({ preventScroll: true });
        };
        lista.appendChild(boton);
    });
}

function prepararNotasPOS() {
    const modal = document.getElementById('pos-notas-modal');
    const picker = document.getElementById('pos-notas-selector');
    picker?.addEventListener('keydown', event => {
        const trigger = document.getElementById('pos-notas-producto-trigger');
        const abierto = trigger.getAttribute('aria-expanded') === 'true';
        if (event.key === 'Escape' && abierto) {
            event.preventDefault(); event.stopPropagation(); alternarSelectorNotaPOS(false); trigger.focus(); return;
        }
        if (event.target === trigger && ['ArrowDown', 'ArrowUp'].includes(event.key)) {
            event.preventDefault(); alternarSelectorNotaPOS(true, true); return;
        }
        if (!abierto || event.target === trigger) return;
        const opciones = Array.from(picker.querySelectorAll('[role="option"]'));
        const indice = opciones.indexOf(document.activeElement);
        let siguiente;
        if (event.key === 'ArrowDown') siguiente = Math.min(indice + 1, opciones.length - 1);
        else if (event.key === 'ArrowUp') siguiente = Math.max(indice - 1, 0);
        else if (event.key === 'Home' && indice >= 0) siguiente = 0;
        else if (event.key === 'End' && indice >= 0) siguiente = opciones.length - 1;
        if (siguiente !== undefined) { event.preventDefault(); opciones[siguiente]?.focus(); }
    });
    picker?.addEventListener('focusout', event => {
        if (!picker.contains(event.relatedTarget)) alternarSelectorNotaPOS(false);
    });
    modal?.addEventListener('close', () => {
        if (modal.open) return;
        alternarSelectorNotaPOS(false);
        borradorNotasPOS = [];
        document.body.classList.remove('notas-open');
        document.getElementById('pos-btn-nota')?.focus({ preventScroll: true });
    });
    modal?.addEventListener('keydown', event => {
        if (event.key === 'Escape') event.stopPropagation();
    });
    modal?.addEventListener('click', event => {
        if (picker && !picker.contains(event.target)) alternarSelectorNotaPOS(false);
        if (event.target === modal) cerrarNotasPOS();
    });
}

function seleccionarItemParaEditarPOS(index, origen, tipo = 'toppings') {
    const item = posCarrito[index];
    if (!item) return;
    seleccionarItemPOS(item);
    actualizarInfoSeleccionPOS();

    abrirModalToppings(origen, tipo);
}

function cambiarCantidadItemPOS(index, cambio) {
    const item = posCarrito[index];
    if (!item) return;

    if (cambio !== -1 && cambio !== 1) return;
    if (cambio === 1) {
        const nuevo = {
            idUnico: Date.now(),
            producto: item.producto,
            cantidad: 1,
            toppings: [],
            sabores: [],
            frutas: []
        };
        posCarrito.splice(index + 1, 0, nuevo);
        seleccionarItemPOS(nuevo);
    } else {
        const cantidadActual = Number(item.cantidad);
        const nueva = (Number.isInteger(cantidadActual) && cantidadActual > 0 ? cantidadActual : 1) - 1;
        if (nueva <= 0) {
            posCarrito.splice(index, 1);
            if (itemPosSeleccionado === item) seleccionarItemPOS(null);
        } else {
            item.cantidad = nueva;
        }
    }

    renderizarPOS();
    actualizarInfoSeleccionPOS();
    actualizarVistaPedidoPOS();
    if (cambio === 1) {
        const fila = document.getElementById('pos-order-items')?.children[index + 1];
        fila?.focus({ preventScroll: true });
        fila?.scrollIntoView({ block: 'center', inline: 'nearest',
            behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
        const estado = document.getElementById('pos-live-status');
        if (estado) estado.textContent = `Se agregó otra unidad de ${item.producto.nombreProducto || item.producto.nombre_producto} sin toppings, frutas ni sabores.`;
    }
}

let temporizadorBackdropCarrito;
let frameBackdropCarrito;
function alternarCarritoMovil(abrir) {
    const panel = document.querySelector('.pos-order-panel');
    const trigger = document.getElementById('pos-mobile-cart-trigger');
    const backdrop = document.getElementById('pos-cart-backdrop');
    if (!panel || !trigger) return;

    const expandir = abrir === undefined ? !panel.classList.contains('is-open') : Boolean(abrir);
    panel.classList.toggle('is-open', expandir);
    document.body.classList.toggle('cart-open', expandir);
    trigger.setAttribute('aria-expanded', String(expandir));
    const contenido = document.getElementById('pos-mobile-cart-content');
    if (contenido) contenido.inert = !expandir && window.matchMedia('(max-width: 720px)').matches;
    if (expandir) {
        panel.setAttribute('role', 'dialog');
        panel.setAttribute('aria-modal', 'true');
    } else {
        panel.removeAttribute('role');
        panel.removeAttribute('aria-modal');
    }
    if (backdrop) {
        clearTimeout(temporizadorBackdropCarrito);
        cancelAnimationFrame(frameBackdropCarrito);
        if (expandir) {
            backdrop.hidden = false;
            frameBackdropCarrito = requestAnimationFrame(() => backdrop.classList.add('is-visible'));
        } else {
            backdrop.classList.remove('is-visible');
            if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) backdrop.hidden = true;
            else temporizadorBackdropCarrito = setTimeout(() => { backdrop.hidden = true; }, 260);
        }
    }
    if (!expandir) trigger.focus({ preventScroll: true });
}

function abrirModalVaciar() {
    if (posCarrito.length === 0) return;
    document.getElementById('modal-confirmar-vaciar').style.display = 'flex';
}

function cerrarModalVaciar() {
    document.getElementById('modal-confirmar-vaciar').style.display = 'none';
}

function ejecutarVaciarCarrito() {
    posCarrito = [];
    seleccionarItemPOS(null);
    cerrarModalVaciar();
    actualizarVistaPedidoPOS();
    renderizarPOS();
    actualizarInfoSeleccionPOS();
}

let retornoFocoToppingsPOS;
let origenToppingsCarritoPOS = false;
let fondoToppingsPOS = [];
let cierreToppingsPOS;
let frameToppingsPOS;

function abrirModalToppings(origen, tipo = 'toppings') {
    if (!productoPosSeleccionado) {
        const estado = document.getElementById('pos-live-status');
        if (estado) estado.textContent = 'Agrega un producto al pedido para elegir sus toppings.';
        return;
    }
    const modal = document.getElementById('modal-toppings-completo');
    if (!modal) return;
    clearTimeout(cierreToppingsPOS);
    cancelAnimationFrame(frameToppingsPOS);
    if (!document.body.classList.contains('toppings-open')) {
        retornoFocoToppingsPOS = origen || document.activeElement;
        origenToppingsCarritoPOS = Boolean(retornoFocoToppingsPOS?.closest('.btn-edit-toppings'));
        fondoToppingsPOS = Array.from(document.querySelectorAll(
            '.cajero-header, .pos-container-layout, .tulas-footer, #pos-back-to-top, #pos-cart-backdrop'
        )).map(elemento => ({ elemento, inert: elemento.inert }));
        fondoToppingsPOS.forEach(({ elemento }) => { elemento.inert = true; });
    }
    if (tipo !== 'cantidades' && !adicionalesDisponiblesPara(productoPosSeleccionado, tipo).length) {
        tipo = ['toppings', 'frutas', 'sabores'].find(valor => adicionalesDisponiblesPara(productoPosSeleccionado, valor).length) || 'toppings';
    }
    document.getElementById('input-buscar-modal-top').value = '';
    document.getElementById('pos-topping-product').textContent =
        productoPosSeleccionado.nombreProducto || productoPosSeleccionado.nombre_producto;
    document.getElementById('grid-modal-todos-toppings').scrollTop = 0;
    cambiarTipoAdicionalPOS(tipo);
    document.body.classList.add('toppings-open');
    modal.style.display = 'flex';
    modal.inert = false;
    modal.classList.remove('is-closing');
    frameToppingsPOS = requestAnimationFrame(() => {
        frameToppingsPOS = requestAnimationFrame(() => modal.classList.add('is-visible'));
    });
    modal.querySelector('.btn-close-pos-modal').focus({ preventScroll: true });
}

function cerrarModalToppings() {
    const modal = document.getElementById('modal-toppings-completo');
    if (!modal || !document.body.classList.contains('toppings-open') || modal.classList.contains('is-closing')) return;
    cancelAnimationFrame(frameToppingsPOS);
    modal.classList.remove('is-visible');
    modal.classList.add('is-closing');
    modal.inert = true;
    const finalizar = () => {
        modal.style.display = 'none';
        modal.classList.remove('is-closing');
        document.body.classList.remove('toppings-open');
        fondoToppingsPOS.forEach(({ elemento, inert }) => { elemento.inert = inert; });
        fondoToppingsPOS = [];
        // El carrito reconstruye sus filas al cambiar toppings; recupera su nuevo botón.
        const focoCarrito = Array.from(document.querySelectorAll('.btn-edit-toppings'))
            .find(boton => boton.dataset.itemIndex === String(posCarrito.indexOf(itemPosSeleccionado))
                && boton.classList.contains('btn-edit-cantidades') === Boolean(retornoFocoToppingsPOS?.classList.contains('btn-edit-cantidades')));
        const destino = origenToppingsCarritoPOS ? focoCarrito : retornoFocoToppingsPOS;
        (destino?.isConnected ? destino : document.getElementById('pos-mobile-cart-trigger'))
            ?.focus({ preventScroll: true });
    };
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) finalizar();
    else cierreToppingsPOS = setTimeout(finalizar, window.matchMedia('(max-width: 720px)').matches ? 360 : 170);
}

function actualizarResumenModalToppingsPOS() {
    const contador = document.getElementById('pos-topping-count');
    const total = document.getElementById('pos-topping-total');
    if (!contador || !total || !productoPosSeleccionado) return;
    const item = itemPosSeleccionado;
    const cantidad = item?.cantidad || 1;
    const precioToppings = costoGrupoAdicional(productoPosSeleccionado, toppingsSeleccionados, 'toppings') + costoGrupoAdicional(productoPosSeleccionado, frutasSeleccionadas, 'frutas');
    const partes = [];
    if (toppingsSeleccionados.length) partes.push(`${toppingsSeleccionados.length} ${toppingsSeleccionados.length === 1 ? 'topping' : 'toppings'}`);
    if (saboresSeleccionados.length) partes.push(`${saboresSeleccionados.length} ${saboresSeleccionados.length === 1 ? 'sabor' : 'sabores'}`);
    partes.push(textoCupoAdicional(productoPosSeleccionado, tipoAdicionalPOS === 'frutas' ? frutasSeleccionadas : toppingsSeleccionados, tipoAdicionalPOS));
    contador.textContent = tipoAdicionalPOS === 'sabores' ? (partes.slice(0, -1).join(' · ') || 'Sin sabores') : partes.at(-1);
    if (tipoAdicionalPOS === 'cantidades') {
        contador.textContent = ['toppings', 'frutas']
            .filter(tipo => opcionesCantidadPOS.some(opcion => opcion.tipo === tipo))
            .map(tipo => textoCupoAdicional(productoPosSeleccionado, item[tipo], tipo)).join(' · ')
            || 'Sin toppings ni frutas seleccionados';
    }
    total.textContent = `${cantidad > 1 ? `${cantidad} unidades · ` : ''}${fmtDinero((parseFloat(productoPosSeleccionado.precio || 0) + precioToppings) * cantidad)}`;
}

function cambiarTipoAdicionalPOS(tipo) {
    const anterior = tipoAdicionalPOS;
    tipoAdicionalPOS = tipo;
    const cantidades = tipo === 'cantidades';
    const modal = document.getElementById('modal-toppings-completo');
    modal.classList.toggle('is-quantity-mode', cantidades);
    if (cantidades) {
        opcionesCantidadPOS = ['toppings', 'frutas'].flatMap(tipo =>
            Array.from(new Map((itemPosSeleccionado[tipo] || []).map(adicional =>
                [idProductoPOS(adicional), { adicional, tipo }])).values()));
        document.getElementById('pos-topping-title').textContent = 'Cantidad de extras';
        modal.querySelector('.btn-close-pos-modal').setAttribute('aria-label', 'Cerrar cantidad de extras');
        document.getElementById('grid-modal-todos-toppings').replaceChildren();
        filtrarModalToppings();
        actualizarResumenModalToppingsPOS();
        return;
    }
    for (const valor of ['toppings', 'sabores', 'frutas']) {
        const boton = document.getElementById(`pos-tab-${valor}`);
        boton.hidden = valor !== 'toppings' && adicionalesDisponiblesPara(productoPosSeleccionado, valor).length === 0;
        boton.setAttribute('aria-pressed', String(valor === tipo));
    }
    document.getElementById('pos-topping-title').textContent = tipo === 'sabores' ? 'Elige los sabores' : tipo === 'frutas' ? 'Elige las frutas' : 'Elige los toppings';
    document.querySelector('#modal-toppings-completo .btn-close-pos-modal').setAttribute('aria-label', `Cerrar ${tipo}`);
    const buscador = document.getElementById('input-buscar-modal-top');
    buscador.value = '';
    buscador.placeholder = tipo === 'sabores' ? 'Buscar sabor…' : tipo === 'frutas' ? 'Buscar fruta…' : 'Buscar topping o salsa…';
    buscador.setAttribute('aria-label', tipo === 'sabores' ? 'Buscar sabor' : tipo === 'frutas' ? 'Buscar fruta' : 'Buscar topping');
    document.getElementById('grid-modal-todos-toppings').scrollTop = 0;
    filtrarModalToppings();
    actualizarResumenModalToppingsPOS();
    if (anterior !== tipo && document.getElementById('modal-toppings-completo').classList.contains('is-visible')
            && !window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
        const grid = document.getElementById('grid-modal-todos-toppings');
        grid.getAnimations().forEach(animacion => animacion.cancel());
        grid.animate([{ opacity: .45, transform: 'translateY(6px)' }, { opacity: 1, transform: 'translateY(0)' }],
            { duration: 180, easing: 'cubic-bezier(.2,.8,.2,1)' });
    }
}

function filtrarModalToppings() {
    if (tipoAdicionalPOS === 'cantidades') {
        renderizarCantidadesExtrasPOS();
        return;
    }
    const texto = (document.getElementById('input-buscar-modal-top')?.value || '').toLowerCase();
    const container = document.getElementById('grid-modal-todos-toppings');
    if (!container) return;

    const toppings = adicionalesDisponiblesPara(productoPosSeleccionado, tipoAdicionalPOS)
        .filter(p => (p.nombreProducto || p.nombre_producto || '').toLowerCase().includes(texto));
    sincronizarTarjetasToppingsPOS(container, toppings, tipoAdicionalPOS);
    if (!toppings.length) {
        const mensaje = texto ? 'No encontramos opciones para esta búsqueda.'
            : tipoAdicionalPOS === 'toppings' && saboresDisponiblesPara(productoPosSeleccionado).length
                ? 'Este producto no tiene toppings disponibles. Puedes elegir sus sabores en la pestaña Sabores.'
                : 'Este producto no tiene opciones disponibles.';
        container.innerHTML = `<p class="pos-topping-empty">${mensaje}</p>`;
    }
}

function cambiarCantidadAdicionalPOS(adicional, tipo, cambio) {
    if (!posCarrito.includes(itemPosSeleccionado) || !['toppings', 'frutas'].includes(tipo)
            || (cambio !== -1 && cambio !== 1)) return;
    const seleccion = tipo === 'frutas' ? frutasSeleccionadas : toppingsSeleccionados;
    const id = idProductoPOS(adicional);
    if (cambio === 1) {
        if (seleccion.length >= 25 || !adicionalesDisponiblesPara(productoPosSeleccionado, tipo)
                .some(opcion => idProductoPOS(opcion) === id)) return;
        seleccion.push(adicional);
    } else {
        const indice = seleccion.map(idProductoPOS).lastIndexOf(id);
        if (indice < 0) return;
        seleccion.splice(indice, 1);
    }
    itemPosSeleccionado[tipo] = [...seleccion];
    actualizarInfoSeleccionPOS();
    actualizarVistaPedidoPOS();
    renderizarCantidadesExtrasPOS();
    actualizarResumenModalToppingsPOS();
}

function renderizarCantidadesExtrasPOS() {
    const container = document.getElementById('grid-modal-todos-toppings');
    if (!opcionesCantidadPOS.length) {
        container.innerHTML = '<p class="pos-topping-empty">Selecciona toppings o frutas en Extras para ajustar sus cantidades.</p>';
        return;
    }
    const filas = new Map(Array.from(container.querySelectorAll('.pos-extra-quantity-row'))
        .map(fila => [fila.dataset.key, fila]));
    for (const { adicional, tipo } of opcionesCantidadPOS) {
        const id = idProductoPOS(adicional);
        const key = `${tipo}-${id}`;
        const nombre = adicional.nombreProducto || adicional.nombre_producto;
        const seleccion = tipo === 'frutas' ? frutasSeleccionadas : toppingsSeleccionados;
        const cantidad = seleccion.filter(opcion => idProductoPOS(opcion) === id).length;
        const { cupo, opciones, preparacion } = grupoAdicionalConCupo(productoPosSeleccionado, seleccion, tipo);
        const cobradas = preparacion && idProductoPOS(preparacion) === id ? cantidad
            : opciones.slice(cupo).filter(opcion => idProductoPOS(opcion) === id).length;
        const incluidas = cantidad - cobradas;
        const fila = filas.get(key) || document.createElement('div');
        if (!filas.has(key)) {
            fila.className = 'pos-extra-quantity-row';
            fila.dataset.key = key;
            const imagen = adicional.urlImagen || adicional.url_imagen || 'lastulas.jpg';
            fila.innerHTML = `<div class="pos-extra-quantity-main">
                    <img class="pos-extra-quantity-image" src="${esc(imagen)}" width="48" height="48" loading="lazy" decoding="async" alt="" onerror="this.onerror=null;this.src='lastulas.jpg'">
                    <div class="pos-extra-quantity-copy"><strong></strong><small></small></div>
                </div>
                <div class="pos-extra-quantity-controls">
                    <button type="button" class="btn-qty" aria-label="Quitar una porción de ${esc(nombre)}">−</button>
                    <strong class="pos-extra-quantity-value"></strong>
                    <button type="button" class="btn-qty" aria-label="Agregar una porción de ${esc(nombre)}">+</button>
                </div>`;
            fila.querySelector('.pos-extra-quantity-copy strong').textContent = nombre;
            const [menos, mas] = fila.querySelectorAll('button');
            menos.onclick = () => cambiarCantidadAdicionalPOS(adicional, tipo, -1);
            mas.onclick = () => cambiarCantidadAdicionalPOS(adicional, tipo, 1);
            container.appendChild(fila);
        }
        fila.querySelector('.pos-extra-quantity-value').textContent = String(cantidad);
        const etiqueta = tipo === 'frutas' ? 'Fruta' : 'Topping';
        const etiquetaIncluido = tipo === 'frutas' ? 'incluida' : 'incluido';
        const incluido = incluidas ? `${incluidas} ${etiquetaIncluido}${incluidas === 1 ? '' : 's'} · ` : '';
        const costo = fmtDinero(cobradas * Math.round(Number(adicional.precio || 0) * 100) / 100).replace(' ', '\u00a0');
        fila.querySelector('.pos-extra-quantity-copy small').textContent = cantidad
            ? `${etiqueta} · ${incluido}+ ${costo}`
            : `${etiqueta} · Sin seleccionar`;
        const [menos, mas] = fila.querySelectorAll('button');
        menos.disabled = cantidad === 0;
        mas.disabled = seleccion.length >= 25 || !adicionalesDisponiblesPara(productoPosSeleccionado, tipo)
            .some(opcion => idProductoPOS(opcion) === id);
        if (menos.disabled && document.activeElement === menos) mas.focus({ preventScroll: true });
        else if (mas.disabled && document.activeElement === mas) menos.focus({ preventScroll: true });
    }
}

let frameVolverArribaPOS;
function actualizarVolverArribaPOS() {
    cancelAnimationFrame(frameVolverArribaPOS);
    frameVolverArribaPOS = requestAnimationFrame(() => {
        const boton = document.getElementById('pos-back-to-top');
        if (!boton) return;
        const cabecera = document.querySelector('.cajero-header');
        const borde = Math.max(0, cabecera?.getBoundingClientRect().bottom || 0);
        const sextoProducto = document.querySelector('#pos-grid-productos .pos-product-card:nth-child(6)');
        boton.style.top = `${borde + 12}px`;
        boton.hidden = !sextoProducto || sextoProducto.getBoundingClientRect().bottom > borde;
    });
}

function volverArribaPOS() {
    window.scrollTo({ top: 0, behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
    document.getElementById('titulo-seccion-pos')?.focus({ preventScroll: true });
}

function prepararAccesosPOS() {
    window.addEventListener('scroll', actualizarVolverArribaPOS, { passive: true });
    window.addEventListener('resize', actualizarVolverArribaPOS, { passive: true });
    const modal = document.getElementById('modal-toppings-completo');
    modal?.addEventListener('click', event => {
        if (event.target === modal) cerrarModalToppings();
    });
    modal?.addEventListener('keydown', event => {
        if (event.key === 'Escape') {
            event.preventDefault();
            event.stopPropagation();
            cerrarModalToppings();
        }
        if (event.key !== 'Tab') return;
        const controles = Array.from(modal.querySelectorAll('button:not(:disabled), input:not(:disabled)'))
            .filter(elemento => elemento.getClientRects().length > 0);
        const primero = controles[0];
        const ultimo = controles[controles.length - 1];
        if (event.shiftKey && document.activeElement === primero) {
            event.preventDefault(); ultimo.focus();
        } else if (!event.shiftKey && document.activeElement === ultimo) {
            event.preventDefault(); primero.focus();
        }
    });
}

function irACobrarPOS() {
    if (posCarrito.length === 0) {
        mostrarAvisoSistema('Agrega al menos un producto a la orden antes de continuar.');
        return;
    }
    guardarCarrito();
    navegarConTransicion('cajero_pago.html');
}

// =====================================================
// 4. MÓDULO DE PAGO & CHECKOUT
// =====================================================
// Fija un valor en un campo de monto marcándolo como "autogenerado":
// así el siguiente toque del teclado numérico empieza desde cero en vez de concatenar.
function fijarMontoCampo(input, valor) {
    if (!input) return;
    delete input.dataset.manual;
    input.value = valor;
}

async function cargarPantallaPago() {
    const botonCobrar = document.querySelector('.btn-finalize-sale');
    if (botonCobrar) botonCobrar.disabled = true;
    inicializarComprobantePorDefecto();
    await cargarConfigNegocio();
    try {
        await cargarCategoriasAdicionalesPOS();
    } catch (error) {
        mostrarAvisoSistema('No se pudo cargar el catálogo. Recarga la página para continuar.', 'error');
        return;
    }

    const carritoGuardado = parseCarrito();
    let totalGeneral = 0;
    let cantTotal = 0;

    carritoGuardado.forEach(item => {
        const precioBase = parseFloat(item.producto.precio || 0);
        const totalTop = costoAdicionalesItem(item);
        totalGeneral += (precioBase + totalTop) * item.cantidad;
        cantTotal += item.cantidad;
    });

    const cantProdsEl = document.getElementById('pago-cant-productos');
    if (cantProdsEl) cantProdsEl.innerText = `Total productos (${cantTotal})`;

    // Desglose fiscal (punto 12): base imponible, IGV y total. El total es el
    // mismo de antes; lo nuevo es que el cajero ve de cuanto es el impuesto.
    pintarDesgloseIgv(
        { base: 'pago-resumen-subtotal', igv: 'pago-resumen-igv', total: 'pago-resumen-total', porcentaje: 'pago-igv-pct' },
        totalGeneral
    );

    const inputEf = document.getElementById('input-monto-efectivo');
    const inputDig = document.getElementById('input-monto-digital');
    seleccionarMetodoPago('EFECTIVO', document.querySelector('[data-metodo-pago="EFECTIVO"]'));

    if (inputEf) inputEf.addEventListener('focus', () => campoActivoTeclado = 'efectivo');
    if (inputDig) inputDig.addEventListener('focus', () => campoActivoTeclado = 'digital');
    if (botonCobrar) botonCobrar.disabled = false;
}

// Al abrir el pago, Nota de venta queda seleccionada con los campos vacíos.
function inicializarComprobantePorDefecto() {
    const docLabel = document.getElementById('label-documento-cliente');
    const docInput = document.getElementById('cliente-doc-input');
    const nombreInput = document.getElementById('cliente-nombre-input');

    if (docLabel) docLabel.innerText = 'DNI (8 dígitos) o CE (9 a 12 dígitos)';
    if (docInput) { docInput.placeholder = 'DNI o documento de extranjería'; docInput.value = ''; docInput.maxLength = 12; }
    if (nombreInput) nombreInput.value = '';
    const notaVenta = document.querySelector('input[name="tipoComprobanteRadio"][value="NOTA_VENTA"]');
    seleccionarComprobante('NOTA_VENTA', notaVenta?.closest('.voucher-radio-option'));
}

function seleccionarMetodoPago(metodo, btn) {
    const eraCombinado = esPagoCombinado(metodoPagoActual);
    metodoPagoActual = metodo;
    document.querySelectorAll('.pay-method-btn').forEach(b => {
        const activo = b === btn;
        b.classList.toggle('active', activo);
        b.setAttribute('aria-pressed', String(activo));
    });

    const banner = document.getElementById('pago-banner-info');
    const inputEf = document.getElementById('input-monto-efectivo');
    const inputDig = document.getElementById('input-monto-digital');
    const labelDigital = document.getElementById('label-digital-monto');
    const totalVenta = obtenerTotalCalculado();
    const combinado = esPagoCombinado(metodo);
    const canalDigital = metodo === 'YAPE' || metodo === 'EFECTIVO_YAPE' ? 'Yape' : 'POS';
    document.getElementById('pago-campo-efectivo').hidden = metodo === 'POS' || metodo === 'YAPE';
    document.getElementById('pago-campo-digital').hidden = metodo === 'EFECTIVO';
    document.getElementById('pago-separador-montos').hidden = !combinado;
    document.getElementById('contenedor-desglose-pago').classList.toggle('is-combined', combinado);
    campoActivoTeclado = metodo === 'POS' || metodo === 'YAPE' ? 'digital' : 'efectivo';

    if (metodo === 'EFECTIVO') {
        banner.innerHTML = '<strong>Pago en efectivo.</strong> Ingresa el dinero recibido para calcular el vuelto.';
        if (inputEf) { fijarMontoCampo(inputEf, totalVenta.toFixed(2)); inputEf.disabled = false; }
        if (inputDig) { fijarMontoCampo(inputDig, '0.00'); inputDig.disabled = true; }
        calcularVueltoYMontos('efectivo');
    } else if (metodo === 'POS' || metodo === 'YAPE') {
        banner.innerHTML = `<strong>Pago con ${canalDigital}.</strong> Cobra el total ${metodo === 'POS' ? 'con tarjeta en el POS' : 'por Yape'}.`;
        if (labelDigital) labelDigital.innerText = `Monto en ${canalDigital} (S/)`;
        if (inputEf) { fijarMontoCampo(inputEf, '0.00'); inputEf.disabled = true; }
        if (inputDig) { fijarMontoCampo(inputDig, totalVenta.toFixed(2)); inputDig.disabled = false; }
        calcularVueltoYMontos('digital');
    } else {
        banner.innerHTML = `<strong>Efectivo + ${canalDigital}.</strong> Ingresa uno de los montos; el otro se calcula automáticamente.`;
        if (labelDigital) labelDigital.innerText = `Parte en ${canalDigital} (S/)`;
        // Cambiar el canal digital conserva el reparto que el cajero ya ingresó.
        if (!eraCombinado) {
            const mitad = (totalVenta / 2).toFixed(2);
            fijarMontoCampo(inputEf, mitad);
            fijarMontoCampo(inputDig, (totalVenta - parseFloat(mitad)).toFixed(2));
        }
        if (inputEf) inputEf.disabled = false;
        if (inputDig) inputDig.disabled = false;
        calcularVueltoYMontos('efectivo');
    }
}

function esPagoCombinado(metodo) {
    return metodo === 'EFECTIVO_POS' || metodo === 'EFECTIVO_YAPE' || metodo === 'EFECTIVO_COMBINADO';
}

function calcularVueltoYMontos(origen) {
    const total = obtenerTotalCalculado();
    const inputEf = document.getElementById('input-monto-efectivo');
    const inputDig = document.getElementById('input-monto-digital');
    const vueltoEl = document.getElementById('val-vuelto-calculado');

    const montoEf = parseFloat(inputEf?.value) || 0;
    const montoDig = parseFloat(inputDig?.value) || 0;

    const pintarVuelto = (texto, esFalta) => {
        if (!vueltoEl) return;
        vueltoEl.innerText = texto;
        vueltoEl.style.color = esFalta ? '#DC2626' : 'var(--text-dark)';
    };

    if (metodoPagoActual === 'EFECTIVO') {
        const diferencia = total - montoEf;
        if (diferencia > 0) {
            pintarVuelto(`Falta S/ ${diferencia.toFixed(2)}`, true);
        } else {
            pintarVuelto(`Vuelto: S/ ${Math.abs(diferencia).toFixed(2)}`, false);
        }
    } else if (esPagoCombinado(metodoPagoActual)) {
        // No se sobregira nunca un campo a 0: si el efectivo ya cubre el total,
        // lo digital queda en 0 y el vuelto real se muestra correctamente.
        if (origen === 'efectivo') {
            const falta = total - montoEf;
            if (inputDig) fijarMontoCampo(inputDig, Math.max(0, falta).toFixed(2));
        } else if (origen === 'digital') {
            const falta = total - montoDig;
            if (inputEf) fijarMontoCampo(inputEf, Math.max(0, falta).toFixed(2));
        }

        // Releer del DOM: el campo "faltante" acaba de ser autollenado.
        const efFinal = parseFloat(inputEf?.value) || 0;
        const digFinal = parseFloat(inputDig?.value) || 0;
        const totalRecibido = efFinal + digFinal;
        if (totalRecibido < total) {
            pintarVuelto(`Falta S/ ${(total - totalRecibido).toFixed(2)}`, true);
        } else {
            pintarVuelto(`Vuelto: S/ ${(totalRecibido - total).toFixed(2)}`, false);
        }
    }
}

function teclearNumeroPago(num) {
    const input = campoActivoTeclado === 'efectivo'
        ? document.getElementById('input-monto-efectivo')
        : document.getElementById('input-monto-digital');

    if (!input || input.disabled) return;

    // Si el valor fue autogenerado (mitad, resto), el primer toque empieza desde cero.
    if (input.dataset.manual !== '1') {
        input.dataset.manual = '1';
        input.value = (num === '.') ? '0.' : num;
        calcularVueltoYMontos(campoActivoTeclado);
        return;
    }

    let val = input.value.replace(/[^0-9.]/g, '');
    if (num === '.') {
        if (!val.includes('.')) val += '.';
    } else {
        if (val.includes('.') && val.split('.')[1].length >= 2) return; // máximo 2 decimales
        val += num;
    }
    input.value = val;
    calcularVueltoYMontos(campoActivoTeclado);
}

function borrarDigitoPago() {
    const input = campoActivoTeclado === 'efectivo'
        ? document.getElementById('input-monto-efectivo')
        : document.getElementById('input-monto-digital');
    if (!input || input.disabled) return;
    input.dataset.manual = '1';
    input.value = input.value.slice(0, -1) || '0';
    calcularVueltoYMontos(campoActivoTeclado);
}

function limpiarTecladoPago() {
    const input = campoActivoTeclado === 'efectivo'
        ? document.getElementById('input-monto-efectivo')
        : document.getElementById('input-monto-digital');
    if (!input || input.disabled) return;
    input.dataset.manual = '1';
    input.value = '0';
    calcularVueltoYMontos(campoActivoTeclado);
}

function confirmarMontoIngresado() {
    const total = obtenerTotalCalculado();
    const ef = parseFloat(document.getElementById('input-monto-efectivo')?.value) || 0;
    const dig = parseFloat(document.getElementById('input-monto-digital')?.value) || 0;
    const recibido = ef + dig;

    if (recibido < total) {
        mostrarAvisoSistema(`Falta S/ ${(total - recibido).toFixed(2)} por cobrar al cliente.`);
    } else if (recibido > total) {
        mostrarAvisoSistema(`Vuelto a devolver: S/ ${(recibido - total).toFixed(2)}.`);
    } else {
        mostrarAvisoSistema('Montos de pago confirmados.');
    }
}

/**
 * Punto 7: se elige por CODIGO canonico, no por la etiqueta.
 *
 * Antes el value era 'Boleta simple' / 'Factura' y eso es justo lo que se
 * guardaba en ventas.tipo_comprobante. Con el mismo comprobante escrito de
 * dos formas, el reporte contaba dos veces la misma boleta. Ahora el codigo es
 * lo unico que viaja al backend y la etiqueta solo se muestra.
 */
function seleccionarComprobante(tipo, labelElement) {
    comprobanteActual = tipo;

    document.querySelectorAll('.voucher-radio-option').forEach(el => {
        el.classList.remove('active');
        const radio = el.querySelector('input[type="radio"]');
        if (radio) radio.checked = false;
    });

    if (labelElement) {
        labelElement.classList.add('active');
        const radio = labelElement.querySelector('input[type="radio"]');
        if (radio) radio.checked = true;
    }

    const docLabel = document.getElementById('label-documento-cliente');
    const docInput = document.getElementById('cliente-doc-input');
    const nombreInput = document.getElementById('cliente-nombre-input');

    // Limpiar cualquier mensaje de estado de una búsqueda anterior
    limpiarEstadoBusquedaDocumento();

    const notaVenta = tipo === 'NOTA_VENTA';
    document.getElementById('cliente-documento-campo').hidden = notaVenta;
    document.getElementById('label-nombre-cliente').textContent = notaVenta ? 'Nombre (opcional)' : 'Nombre o razón social';
    if (docInput) docInput.disabled = notaVenta;
    if (notaVenta) {
        if (docInput) docInput.value = '';
    } else if (tipo === 'BOLETA_SIMPLE') {
        if (docLabel) docLabel.innerText = 'Documento (Opcional)';
        if (docInput) { docInput.placeholder = 'Opcional'; docInput.value = ''; docInput.maxLength = 15; }
        if (nombreInput) nombreInput.value = '';
    } else if (tipo === 'FACTURA') {
        if (docLabel) docLabel.innerText = 'Número de RUC (11 dígitos)';
        if (docInput) { docInput.placeholder = 'Ej. 20601234567'; docInput.value = ''; docInput.maxLength = 11; }
        if (nombreInput) nombreInput.value = '';
    } else {
        if (docLabel) docLabel.innerText = 'DNI (8 dígitos) o CE (9 a 12 dígitos)';
        if (docInput) { docInput.placeholder = 'DNI o documento de extranjería'; docInput.value = ''; docInput.maxLength = 12; }
        if (nombreInput) nombreInput.value = '';
    }
}

function montoParaVistaPrevia(valor) {
    const numero = Number(valor);
    return (Number.isFinite(numero) ? numero : 0).toLocaleString('es-PE', {
        minimumFractionDigits: 2,
        maximumFractionDigits: 2
    });
}

function filasClienteComprobante(items, notaVenta) {
    let numero = 0;
    const fila = (nombre, cantidad, precio, importe, adicional = false) => {
        const descripcion = `<td class="receipt-item-description">${adicional ? 'Extra: ' : ''}${esc(nombre || 'Producto')}</td>`;
        if (adicional && !notaVenta) {
            return `<tr class="receipt-extra-row"><td></td>` +
                `<td colspan="3" class="receipt-item-description">Extra: ${esc(nombre || 'Adicional')}` +
                (cantidad > 1 ? ` <small>${esc(cantidad)} × ${montoParaVistaPrevia(precio)}</small>` : '') +
                `</td><td>${montoParaVistaPrevia(importe)}</td></tr>`;
        }
        return `<tr${adicional ? ' class="receipt-extra-row"' : ''}>` +
            (notaVenta ? `<td>${esc(cantidad)}</td>${descripcion}`
                : `<td>${adicional ? '' : ++numero}</td>${descripcion}<td>${esc(cantidad)}</td><td>${montoParaVistaPrevia(precio)}</td>`) +
            `<td>${montoParaVistaPrevia(importe)}</td></tr>`;
    };
    return items.map(item => {
        const cantidad = Number(item.cantidad) || 0;
        // Solo el desglose guardado al cobrar distingue extras de incluidos.
        // Las ventas anteriores mantienen su importe histórico completo.
        const precioBase = item.precioBaseUnitario;
        if (precioBase == null) {
            return fila(item.nombreProducto, cantidad, item.precioUnitario, item.subtotal);
        }
        const adicionales = new Map();
        for (const extra of Array.isArray(item.adicionalesCobrados) ? item.adicionalesCobrados : []) {
            if (Number(extra.precioUnitario) <= 0) continue;
            const clave = `${extra.idProducto ?? extra.nombreProducto}|${Number(extra.precioUnitario)}`;
            const grupo = adicionales.get(clave) || { ...extra, porciones: 0 };
            grupo.porciones++;
            adicionales.set(clave, grupo);
        }
        return fila(item.nombreProducto, cantidad, precioBase, Number(precioBase) * cantidad) +
            Array.from(adicionales.values(), extra => {
                const nombre = extra.porciones > 1 ? `x${extra.porciones} ${extra.nombreProducto}` : extra.nombreProducto;
                const precio = Math.round(Number(extra.precioUnitario) * 100) * extra.porciones / 100;
                return fila(nombre, cantidad, precio, precio * cantidad, true);
            }).join('');
    }).join('');
}

function mostrarVistaPreviaComprobante(venta) {
    const modal = document.getElementById('receipt-preview-modal');
    if (!modal) {
        mostrarAvisoSistema(`La venta ${venta.numeroComprobante || venta.idVenta} quedó registrada, pero no se pudo abrir la vista previa. No vuelvas a cobrar esta misma venta.`);
        navegarConTransicion('cajero.html');
        return;
    }

    const tipo = String(venta.tipoComprobante || '');
    const notaVenta = tipo === 'NOTA_VENTA';
    const titulo = notaVenta ? 'NOTA DE VENTA' : tipo === 'FACTURA' ? 'FACTURA DE VENTA' : 'BOLETA DE VENTA';
    document.getElementById('receipt-preview-heading').textContent = notaVenta
        ? 'Cliente y cocina · 2 copias' : 'Cliente y cocina · 2 comprobantes';
    document.getElementById('receipt-ticket').classList.toggle('is-sale-note', notaVenta);
    document.getElementById('receipt-document-notice').hidden = !notaVenta;
    document.getElementById('receipt-customer-section').hidden = notaVenta;
    document.getElementById('receipt-payment-title').hidden = notaVenta;
    document.getElementById('receipt-preview-title').textContent = titulo;
    document.getElementById('receipt-number').textContent = venta.numeroComprobante || 'Comprobante sin número';

    const fecha = venta.fechaVenta ? new Date(venta.fechaVenta) : null;
    const fechaTexto = fecha && !Number.isNaN(fecha.getTime())
        ? new Intl.DateTimeFormat('es-PE', { dateStyle: 'short', timeStyle: 'short' }).format(fecha)
        : 'No disponible';
    document.getElementById('receipt-sale-meta').innerHTML =
        `<div><span>Fecha y hora</span><strong>${esc(fechaTexto)}</strong></div>` +
        (notaVenta
            ? (venta.clienteNombre && venta.clienteNombre !== 'CLIENTE GENERAL'
                ? `<div><span>Cliente</span><strong>${esc(venta.clienteNombre)}</strong></div>` : '')
            : `<div><span>Cajero</span><strong>${esc(venta.cajeroNombre || 'No disponible')}</strong></div>` +
                `<div><span>Referencia interna</span><strong>${esc(venta.idVenta ?? '—')}</strong></div>`);

    const documento = venta.clienteDocumento ? `Documento: ${esc(venta.clienteDocumento)}` : 'Documento: —';
    document.getElementById('receipt-customer').innerHTML =
        `<div><span>Cliente</span><strong>${esc(venta.clienteNombre || 'CLIENTE GENERAL')}</strong></div>` +
        `<div>${documento}</div>`;

    const items = Array.isArray(venta.detalles) ? venta.detalles : [];
    document.getElementById('receipt-items-head').innerHTML = notaVenta
        ? '<tr><th>CANT.</th><th>PRODUCTO</th><th>IMPORTE</th></tr>'
        : '<tr><th>N°</th><th>DESCRIPCIÓN</th><th>CANT.</th><th>P. UNIT.</th><th>IMPORTE</th></tr>';
    document.getElementById('receipt-items-body').innerHTML = filasClienteComprobante(items, notaVenta);

    const tasa = Number(venta.tasaIgv);
    document.getElementById('receipt-totals').innerHTML =
        (notaVenta ? '' : `<div><span>OP. GRAVADA</span><span>S/ ${montoParaVistaPrevia(venta.subtotal)}</span></div>` +
            `<div><span>IGV${Number.isFinite(tasa) ? ` (${Math.round(tasa * 100)}%)` : ''}</span><span>S/ ${montoParaVistaPrevia(venta.igv)}</span></div>`) +
        `<div class="receipt-grand-total"><strong>TOTAL</strong><strong>S/ ${montoParaVistaPrevia(venta.total)}</strong></div>`;

    const pagos = [];
    if (!notaVenta && Number(venta.montoEfectivo) > 0) {
        pagos.push(`<div><span>Efectivo aplicado</span><span>S/ ${montoParaVistaPrevia(venta.montoEfectivo)}</span></div>`);
    }
    if (!notaVenta && Number(venta.montoDigital) > 0) {
        pagos.push(`<div><span>Pago digital aplicado</span><span>S/ ${montoParaVistaPrevia(venta.montoDigital)}</span></div>`);
    }
    if (Number(venta.vuelto) > 0) {
        pagos.push(`<div><span>Vuelto</span><span>S/ ${montoParaVistaPrevia(venta.vuelto)}</span></div>`);
    }
    document.getElementById('receipt-payment').innerHTML =
        `<div><span>Método</span><strong>${esc(venta.metodoPago || 'No disponible')}</strong></div>` +
        pagos.join('');

    document.getElementById('kitchen-number').textContent = venta.numeroComprobante || `Pedido ${venta.idVenta ?? ''}`;
    document.getElementById('kitchen-sale-meta').innerHTML =
        `<div><span>Fecha y hora</span><strong>${esc(fechaTexto)}</strong></div>` +
        `<div><span>Cajero</span><strong>${esc(venta.cajeroNombre || 'No disponible')}</strong></div>` +
        `<div><span>Pedido</span><strong>${esc(venta.idVenta ?? '—')}</strong></div>` +
        `<div><span>Cliente</span><strong>${esc(venta.clienteNombre || 'CLIENTE GENERAL')}</strong></div>`;
    document.getElementById('kitchen-items').innerHTML = items.map(item =>
        `<li class="kitchen-item">` +
            `<div class="kitchen-product"><strong class="kitchen-quantity">${esc(item.cantidad ?? 0)}×</strong>` +
            `<h4>${esc(item.nombreProducto || 'Producto')}</h4></div>` +
            (item.toppings ? `<p><strong>Toppings:</strong> ${esc(item.toppings)}</p>` : '') +
            (item.frutas ? `<p><strong>Frutas:</strong> ${esc(item.frutas)}</p>` : '') +
            (item.sabores ? `<p><strong>Sabores:</strong> ${esc(item.sabores)}</p>` : '') +
            (item.notas ? `<p class="kitchen-note"><strong>NOTA:</strong> ${esc(item.notas)}</p>` : '') +
        `</li>`
    ).join('');

    modal.classList.add('is-open');
    modal.setAttribute('aria-hidden', 'false');
    modal.querySelector('.receipt-action-button').focus();
}

function imprimirVistaPreviaComprobante() {
    window.print();
}

function cerrarVistaPreviaComprobante() {
    navegarConTransicion('cajero.html');
}

// =====================================================
// AUTOCOMPLETADO DE NOMBRE/RAZÓN SOCIAL POR DNI O RUC
// =====================================================
let _timerBusquedaDocumento = null;
let _consultaDocumento = null;
let _versionDocumento = 0;
let _versionNombreCliente = 0;
let _nombreAutoDocumento = null;

function limpiarEstadoBusquedaDocumento() {
    clearTimeout(_timerBusquedaDocumento);
    _versionDocumento++;
    _consultaDocumento?.abort();
    _consultaDocumento = null;
    const nombre = document.getElementById('cliente-nombre-input');
    if (nombre) nombre.readOnly = false;
    const doc = document.getElementById('cliente-doc-input');
    doc?.removeAttribute('aria-busy');
    doc?.removeAttribute('aria-invalid');
    const estado = document.getElementById('estado-busqueda-documento');
    if (estado) estado.hidden = true;
}

function mostrarEstadoBusquedaDocumento(mensaje, tipo, reintentar = false) {
    const estado = document.getElementById('estado-busqueda-documento');
    const texto = document.getElementById('documento-estado-texto');
    if (!estado || !texto) return;
    estado.hidden = false;
    estado.className = `documento-estado documento-estado--${tipo}`;
    texto.textContent = mensaje;
    document.getElementById('documento-reintentar').hidden = !reintentar;
    document.getElementById('documento-estado-icono').className = tipo === 'loading'
        ? 'fa-solid fa-spinner fa-spin' : tipo === 'ok' ? 'fa-solid fa-circle-check' : 'fa-solid fa-circle-info';
}

async function buscarDocumentoCliente() {
    const docInput = document.getElementById('cliente-doc-input');
    const nombreInput = document.getElementById('cliente-nombre-input');
    if (!docInput || !nombreInput) return;
    limpiarEstadoBusquedaDocumento();
    if (comprobanteActual === 'BOLETA_SIMPLE' || comprobanteActual === 'NOTA_VENTA') return;
    const comprobante = comprobanteActual;
    const numero = docInput.value.trim();
    const tipo = comprobante === 'FACTURA' ? 'ruc' : 'dni';
    const etiqueta = tipo === 'ruc' ? 'RUC' : 'DNI';
    if (tipo === 'dni' && /^[0-9]{9,12}$/.test(numero)) {
        mostrarEstadoBusquedaDocumento('Documento de extranjería: ingresa el nombre manualmente.', 'aviso');
        return;
    }
    if (numero.length !== (tipo === 'ruc' ? 11 : 8)) return;
    if (!validarDocumentoPeru(tipo, numero)) {
        docInput.setAttribute('aria-invalid', 'true');
        mostrarEstadoBusquedaDocumento(tipo === 'dni' ? 'El DNI debe tener 8 dígitos.' : 'Revisa el RUC: su dígito verificador no es válido.', 'error');
        return;
    }
    const controlador = new AbortController();
    _consultaDocumento = controlador;
    const version = _versionDocumento;
    const versionNombre = _versionNombreCliente;
    const vigente = () => version === _versionDocumento && comprobante === comprobanteActual && docInput.value.trim() === numero;
    docInput.setAttribute('aria-busy', 'true');
    mostrarEstadoBusquedaDocumento(tipo === 'ruc' ? 'Buscando razón social por RUC…' : 'Buscando nombre por DNI…', 'loading');
    const timeout = setTimeout(() => controlador.abort(), 20000);
    try {
        const res = await apiFetch(`/documento?tipo=${tipo}&numero=${numero}`, { signal: controlador.signal });
        const data = await res.json();
        if (!vigente()) return;
        if (!res.ok || !data.success || typeof data.nombre !== 'string' || !data.nombre.trim()) {
            const mensaje = typeof data.message === 'string' && data.message.trim() && res.status !== 404 ? data.message : res.status === 404 ? `No se encontró información para ese ${etiqueta}. Ingresa el nombre manualmente o reintenta.`
                : res.status === 503 ? 'La consulta automática no está disponible. Puedes ingresar el nombre manualmente o reintentar.'
                : `No se pudo consultar el ${etiqueta}. Puedes ingresar el nombre manualmente o reintentar.`;
            mostrarEstadoBusquedaDocumento(mensaje, 'aviso', true);
            return;
        }
        // La respuesta nunca reemplaza un nombre que el cajero acaba de editar.
        if (versionNombre === _versionNombreCliente) {
            nombreInput.value = data.nombre.trim();
            _nombreAutoDocumento = nombreInput.value;
            mostrarEstadoBusquedaDocumento(tipo === 'ruc' ? 'Razón social encontrada. Puedes editarla si lo necesitas.' : 'Nombre encontrado. Puedes editarlo si lo necesitas.', 'ok');
        } else {
            mostrarEstadoBusquedaDocumento('Consulta completada. Se conservó el nombre que escribiste.', 'ok');
        }
    } catch (err) {
        if (!vigente() || err.esSesionExpirada || err.esSinPermisos) return;
        mostrarEstadoBusquedaDocumento(controlador.signal.aborted ? 'La consulta tardó demasiado. Ingresa el nombre manualmente o reintenta.' : 'No se pudo conectar con la consulta. Ingresa el nombre manualmente o reintenta.', 'aviso', true);
    } finally {
        clearTimeout(timeout);
        if (vigente()) {
            _consultaDocumento = null;
            nombreInput.readOnly = false;
            docInput.removeAttribute('aria-busy');
        }
    }
}

document.addEventListener('DOMContentLoaded', () => {
    const docInput = document.getElementById('cliente-doc-input');
    const nombreInput = document.getElementById('cliente-nombre-input');
    if (!docInput || !nombreInput) return;
    nombreInput.readOnly = false;
    nombreInput.addEventListener('input', () => { _versionNombreCliente++; _nombreAutoDocumento = null; });
    docInput.addEventListener('input', () => {
        docInput.value = docInput.value.replace(/\D/g, '');
        if (_nombreAutoDocumento !== null && nombreInput.value === _nombreAutoDocumento) nombreInput.value = '';
        _nombreAutoDocumento = null;
        limpiarEstadoBusquedaDocumento();
        _timerBusquedaDocumento = setTimeout(buscarDocumentoCliente, 400);
    });
});

/**
 * Suma de la comanda tal como se ve en pantalla. Solo sirve para MOSTRAR el
 * total mientras el cajero arma el pedido: el valor que se guarda es el que
 * recalcula el servidor con los precios de la base de datos (punto 10).
 */
function obtenerTotalCalculado() {
    const carritoGuardado = parseCarrito();
    return carritoGuardado.reduce((acc, item) => {
        const pBase = parseFloat(item.producto.precio || 0);
        const pTop = costoAdicionalesItem(item);
        return acc + ((pBase + pTop) * item.cantidad);
    }, 0);
}

async function finalizarVentaComprobante() {
    if (ventaEnviando) return;

    const carritoGuardado = parseCarrito();
    if (carritoGuardado.length === 0) {
        mostrarAvisoSistema('No hay productos en el pedido.');
        return;
    }

    if (!obtenerToken()) {
        mostrarAvisoSistema('Tu sesión expiró. Vuelve a iniciar sesión.');
        window.location.href = 'index.html';
        return;
    }

    // Lo que ve el cajero mientras cobra. No es el numero que se guarda.
    const totalEnPantalla = obtenerTotalCalculado();
    const clienteNombre = document.getElementById('cliente-nombre-input')?.value.trim() || 'CLIENTE GENERAL';
    const clienteDoc = document.getElementById('cliente-doc-input')?.value.trim() || '';
    const montoEf = parseFloat(document.getElementById('input-monto-efectivo')?.value) || 0;
    const montoDig = parseFloat(document.getElementById('input-monto-digital')?.value) || 0;

    // El DNI se valida por longitud; solo el RUC tiene dígito verificador.
    if ((comprobanteActual === 'BOLETA_DNI' || comprobanteActual === 'FACTURA') && clienteDoc) {
        const tipoDoc = comprobanteActual === 'FACTURA' ? 'ruc' : 'dni';
        if (!validarDocumentoPeru(tipoDoc, clienteDoc)) {
            mostrarAvisoSistema(tipoDoc === 'dni'
                ? 'Ingresa un DNI de 8 dígitos o un documento de extranjería numérico de 9 a 12 dígitos.'
                : 'El RUC ingresado es inválido (dígito verificador incorrecto). Corrígelo o elige "Boleta simple".');
            return;
        }
    }

    // Punto 7: el comprobante sin documento segun su tipo se rechaza aqui
    // para no gastar el viaje, pero el servidor lo vuelve a exigir: es el que
    // decide, no el formulario.
    if ((comprobanteActual === 'BOLETA_DNI' || comprobanteActual === 'FACTURA') && !clienteDoc) {
        mostrarAvisoSistema(comprobanteActual === 'FACTURA'
            ? 'Una factura necesita el RUC del cliente.'
            : 'Una boleta con DNI necesita el documento del cliente. Elige "Boleta simple" si no lo tiene.');
        return;
    }

    // El vuelto ya no se calcula aqui. Se manda lo que se recibio tal cual y el
    // servidor calcula el vuelto y guarda solo el monto efectivamente cobrado.
    const itemsPayload = carritoGuardado.map(it => ({
        idProducto: it.producto.idProducto || it.producto.id_producto,
        cantidad: it.cantidad,
        toppings: (it.toppings || []).map(t => t.idProducto || t.id_producto),
        sabores: (it.sabores || []).map(t => t.idProducto || t.id_producto),
        frutas: (it.frutas || []).map(t => t.idProducto || t.id_producto),
        notas: it.notas?.trim() || null
    }));

    // OJO: aqui NO viaja total, ni subtotal, ni igv, ni precioUnitario, ni
    // idUsuario. El total lo suma el backend con los precios de la tabla
    // productos y el cajero sale del token. Si se mandara el total, bastaria
    // editarlo en el navegador para vender la carta por un centimo.
    const payloadVenta = {
        clienteNombre: clienteNombre,
        clienteDocumento: clienteDoc,
        tipoComprobante: comprobanteActual,
        metodoPago: metodoPagoActual,
        montoEfectivo: montoEf,
        montoDigital: montoDig,
        items: itemsPayload
    };

    let cobroPendiente;
    try {
        const guardado = sessionStorage.getItem(CLAVE_COBRO_PENDIENTE);
        if (guardado) {
            cobroPendiente = JSON.parse(guardado);
            if (!cobroPendiente?.clave || !cobroPendiente?.payload || !Array.isArray(cobroPendiente.carrito)) {
                throw new Error('El intento de cobro pendiente no se puede leer de forma segura.');
            }
            if (JSON.stringify(payloadVenta) !== JSON.stringify(cobroPendiente.payload)) {
                mostrarAvisoSistema('Hay un cobro anterior sin respuesta. Se reintentará ese mismo pedido para comprobarlo antes de iniciar otro.');
            }
        } else {
            if (!globalThis.crypto?.randomUUID) {
                throw new Error('Este navegador no permite generar una clave segura para el cobro.');
            }
            cobroPendiente = {
                clave: crypto.randomUUID(),
                payload: payloadVenta,
                carrito: carritoGuardado
            };
            sessionStorage.setItem(CLAVE_COBRO_PENDIENTE, JSON.stringify(cobroPendiente));
        }
    } catch (err) {
        errorControlado(err, 'No se pudo preparar un intento de cobro seguro. No se envió la venta.');
        return;
    }

    ventaEnviando = true;
    try {
        const res = await apiFetch(`/ventas`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Idempotency-Key': cobroPendiente.clave
            },
            body: JSON.stringify(cobroPendiente.payload)
        });
        const data = await res.json();

        if (data.success) {
            sessionStorage.removeItem(CLAVE_COBRO_PENDIENTE);
            if (JSON.stringify(parseCarrito()) === JSON.stringify(cobroPendiente.carrito)) {
                posCarrito = [];
                localStorage.removeItem(CLAVE_CARRITO);
            }
            mostrarVistaPreviaComprobante(data);
        } else {
            if (res.status >= 400 && res.status < 500 && res.status !== 409) {
                sessionStorage.removeItem(CLAVE_COBRO_PENDIENTE);
            }
            mostrarAvisoSistema(data.message || 'No se pudo registrar la venta. Intenta de nuevo.');
        }
    } catch (err) {
        errorControlado(err, 'Error de conexión al registrar la venta. Verifica que el servidor esté activo.');
    } finally {
        ventaEnviando = false;
    }
}

// =====================================================
// 5. MÓDULO DE REPORTE DE BOLETAS POR CAJERO
// =====================================================

/** Catálogo de cajeros para filtrar ventas; todos cobran en la caja general. */
async function cargarCajerosParaFiltros() {
    try {
        const res = await apiFetch(`/ventas/cajas`);
        if (!res.ok) throw new Error('Error al cargar cajeros');
        const cajas = await res.json();
        cajerosDisponiblesLista = (cajas || []).map(c => ({
            idUsuario: c.idUsuario,
            nombreCompleto: c.nombre,
            cajaNumero: c.cajaNumero
        }));
        return true;
    } catch (err) {
        console.warn('Error al cargar cajeros para filtros:', err);
        cajerosDisponiblesLista = [];
        return false;
    }
}

const COLORES_CAJA = ['red', 'blue', 'green', 'orange', 'purple', 'teal'];
let solicitudReporteBoletas = 0;

function poblarTarjetasCajeros() {
    const grid = document.getElementById('cajeros-cards-grid');
    if (!grid) return;
    const firma = JSON.stringify(cajerosDisponiblesLista);
    if (grid.dataset.cajeros !== firma) {
        grid.dataset.cajeros = firma;
        grid.replaceChildren();
        cajerosDisponiblesLista.forEach((cajero, idx) => {
            const color = COLORES_CAJA[idx % COLORES_CAJA.length];
            const card = document.createElement('button');
            card.type = 'button';
            card.className = `caja-card-resumen ${color}`;
            card.id = `card-caja-${cajero.idUsuario}`;
            card.style.setProperty('--card-delay', `${Math.min(idx, 5) * 35}ms`);
            card.onclick = () => seleccionarCajaDesdeSelect(String(cajeroFiltroActivo) === String(cajero.idUsuario) ? 'TODOS' : String(cajero.idUsuario));
            card.innerHTML = `<span class="caja-card-top"><span class="caja-card-info"><i class="fa-solid fa-cash-register caja-icon ${color}" aria-hidden="true"></i><span><strong class="caja-card-title">CAJA ${esc(cajero.cajaNumero ?? idx + 1)}</strong><small>Cajero(a): ${esc(cajero.nombreCompleto)}</small></span></span><i class="fa-solid fa-chevron-right arrow-icon" aria-hidden="true"></i></span><span class="caja-stats-row"><span><span>Total boletas</span><strong id="cajero${esc(cajero.idUsuario)}-total-boletas">…</strong></span><span><span>Monto total</span><strong id="cajero${esc(cajero.idUsuario)}-monto-total">…</strong></span></span>`;
            grid.append(card);
        });
        if (!cajerosDisponiblesLista.length) {
            const vacio = document.createElement('p');
            vacio.className = 'cajeros-dashboard-vacio';
            vacio.textContent = 'No hay cajeros activos. Puedes gestionarlos desde Creación de usuario.';
            grid.append(vacio);
        }
    }
    actualizarSeleccionTarjetasCajeros();
}

function actualizarSeleccionTarjetasCajeros() {
    cajerosDisponiblesLista.forEach(c => {
        const card = document.getElementById(`card-caja-${c.idUsuario}`);
        const activo = String(cajeroFiltroActivo) === String(c.idUsuario);
        card?.classList.toggle('active', activo);
        card?.setAttribute('aria-pressed', String(activo));
    });
}

function pintarTotalesPorCaja(lista) {
    const totales = new Map(lista.map(c => [Number(c.idUsuario), c]));
    cajerosDisponiblesLista.forEach(c => {
        const dato = totales.get(Number(c.idUsuario));
        set(`cajero${c.idUsuario}-total-boletas`, dato?.cantidad ?? 0);
        set(`cajero${c.idUsuario}-monto-total`, fmtDinero(dato?.total ?? 0));
    });
}

function poblarSelectReporteCajero() {
    const select = document.getElementById('filtro-reporte-cajero');
    if (!select) return;
    select.innerHTML = '<option value="TODOS">Todos los cajeros...</option>';
    // El value es el id_usuario: es el filtro que entiende el backend.
    cajerosDisponiblesLista.forEach(cajero => {
        const opt = document.createElement('option');
        opt.value = String(cajero.idUsuario);
        opt.innerText = cajero.nombreCompleto;
        select.appendChild(opt);
    });
    select.value = String(cajeroFiltroActivo);
}

function poblarSelectsAnulacion() {
    // Ambos filtros llevan idUsuario como value y se comparan contra
    // b.idUsuario. Antes el value era "Caja N" y se comparaba con
    // b.cajeroNombre (el nombre de la persona) o con b.cajaNombre (otra
    // numeracion distinta): los dos filtros daban lista vacia siempre que se
    // usaran.
    const opciones = (incluirTodas, textoTodas) => {
        let html = `<option value="${incluirTodas}">${textoTodas}</option>`;
        cajerosDisponiblesLista.forEach(cajero => {
            html += `<option value="${esc(cajero.idUsuario)}">${esc(cajero.nombreCompleto)}</option>`;
        });
        return html;
    };

    const selectCaja = document.getElementById('filtro-anulacion-caja');
    if (selectCaja) selectCaja.innerHTML = opciones('TODAS', 'Todos los cajeros');

    const selectCajero = document.getElementById('filtro-anulacion-cajero');
    if (selectCajero) {
        selectCajero.innerHTML = '<option value="TODOS">Todos los cajeros</option>';
        cajerosDisponiblesLista.forEach(cajero => {
            const opt = document.createElement('option');
            opt.value = String(cajero.idUsuario);
            opt.innerText = cajero.nombreCompleto;
            selectCajero.appendChild(opt);
        });
    }
}

// ---------------------------------------------------------------
// PUNTO 4 - PAGINACION Y AGREGACIONES EN EL BACKEND
// ---------------------------------------------------------------
// Antes estas pantallas pedian GET /api/ventas sin parametros y recibian la
// tabla COMPLETA. El filtro se aplicaba en JavaScript sobre ese array, asi
// que no ahorraba nada: se descargaban miles de filas para mostrar 25. Los
// totales se sumaban tambien en el navegador.
//
// Ahora cada pantalla pide una pagina (LIMIT/OFFSET) y los KPIs salen de
// /ventas/resumen, que los calcula PostgreSQL con SUM y COUNT. Si el total de
// la cabecera no cuadrara con la tabla, uno de los dos estaria mal; al venir
// los dos del servidor con los mismos filtros, siempre cuadran.

const TAMANO_PAGINA_VENTAS = 10;

// Estado de paginacion por pantalla, para que el boton de "siguiente" sepa
// donde esta. Sin esto cada recarga vuelve a la pagina 0.
let paginaReporteBoletas = 0;
let paginaAnulacion = 0;
let paginaReporteGeneral = 0;

/** Arma el query string de los filtros, descartando los vacios. */
function queryVentas(filtros = {}) {
    const params = new URLSearchParams();
    Object.entries(filtros).forEach(([k, v]) => {
        if (v !== null && v !== undefined && v !== '' && v !== 'TODOS' && v !== 'TODAS') {
            params.append(k, v);
        }
    });
    return params.toString();
}

/** Filtros del reporte de boletas, leidos del formulario. */
function filtrosReporteBoletas() {
    const metodo = document.getElementById('filtro-reporte-metodo')?.value || 'TODOS';
    return {
        pagina: paginaReporteBoletas,
        tamano: TAMANO_PAGINA_VENTAS,
        caja: (cajeroFiltroActivo !== 'TODOS') ? cajeroFiltroActivo : '',
        metodoPago: metodo !== 'TODOS' ? metodo : '',
        desde: document.getElementById('filtro-fecha-inicio')?.value || '',
        hasta: document.getElementById('filtro-fecha-fin')?.value || '',
        estado: 'EMITIDA'
    };
}

async function cargarReporteBoletas() {
    const solicitud = ++solicitudReporteBoletas;
    const catalogoCargado = await cargarCajerosParaFiltros();
    if (solicitud !== solicitudReporteBoletas) return;
    if (!catalogoCargado) {
        document.getElementById('cajeros-cards-grid')?.replaceChildren();
        document.getElementById('cajeros-cards-grid')?.removeAttribute('data-cajeros');
        document.getElementById('cajeros-cards-grid')?.setAttribute('aria-busy', 'false');
        set('cajeros-dashboard-estado', 'No se pudieron cargar los cajeros. Cambia un filtro o recarga la página para reintentar.');
        return;
    }
    poblarSelectReporteCajero();
    poblarTarjetasCajeros();
    const grid = document.getElementById('cajeros-cards-grid');
    grid?.setAttribute('aria-busy', 'true');
    set('cajeros-dashboard-estado', 'Actualizando las ventas de los cajeros activos…');

    const filtros = filtrosReporteBoletas();
    const qs = queryVentas(filtros);

    try {
        const [resLista, resCajas] = await Promise.all([
            apiFetch(`/ventas?${qs}`),
            apiFetch(`/ventas/por-caja?${queryVentas({ desde: filtros.desde, hasta: filtros.hasta, metodoPago: filtros.metodoPago })}`)
        ]);
        if (solicitud !== solicitudReporteBoletas) return;
        if (resCajas.ok) {
            const totales = await resCajas.json();
            if (solicitud !== solicitudReporteBoletas) return;
            pintarTotalesPorCaja(totales);
            set('cajeros-dashboard-estado', 'Selecciona un cajero para ver sus boletas. Los totales corresponden al rango de fechas y método de pago elegidos.');
        } else {
            cajerosDisponiblesLista.forEach(c => { set(`cajero${c.idUsuario}-total-boletas`, '—'); set(`cajero${c.idUsuario}-monto-total`, '—'); });
            set('cajeros-dashboard-estado', 'No se pudieron cargar los totales. Cambia un filtro o recarga la página para reintentar.');
        }
        grid?.setAttribute('aria-busy', 'false');

        if (!resLista.ok) {
            let mensaje = `Error HTTP ${resLista.status} al cargar las boletas.`;
            try {
                const errorApi = await resLista.json();
                if (errorApi.message) mensaje = errorApi.message;
            } catch (errorLectura) {
                console.warn('No se pudo leer el detalle del error de boletas:', errorLectura);
            }
            throw new Error(mensaje);
        }
        const data = await resLista.json();
        if (solicitud !== solicitudReporteBoletas) return;
        boletasLista = data.contenido || [];
        paginaReporteBoletas = data.pagina || 0;

        renderizarTablaReporteBoletas(boletasLista, data, cajeroFiltroActivo);
        pintarControlesPaginacion('reporte-boletas', data, (nuevaPagina) => {
            paginaReporteBoletas = nuevaPagina;
            cargarReporteBoletas();
        });


    } catch (err) {
        if (solicitud !== solicitudReporteBoletas) return;
        grid?.setAttribute('aria-busy', 'false');
        set('cajeros-dashboard-estado', 'No se pudo actualizar el reporte. Cambia un filtro o recarga la página para reintentar.');
        console.warn('Error al cargar boletas:', err);
        boletasLista = [];
        const tbody = document.getElementById('tabla-reporte-boletas-body');
        if (tbody) {
            const mensaje = err instanceof Error ? err.message : 'Error inesperado al cargar las boletas.';
            tbody.innerHTML = `<tr><td colspan="7" style="text-align:center; padding: 25px; color: #b42318;">No se pudieron cargar las boletas: ${esc(mensaje)}</td></tr>`;
        }
        const paginacion = document.getElementById('label-paginacion-reporte-boletas');
        if (paginacion) paginacion.innerText = 'Error al cargar boletas';
        ['anterior', 'siguiente'].forEach(direccion => {
            const boton = document.getElementById(`btn-pag-reporte-boletas-${direccion}`);
            if (boton) boton.disabled = true;
        });
    }
}

function seleccionarCajaDesdeSelect(valor) {
    cajeroFiltroActivo = valor;
    paginaReporteBoletas = 0;
    const select = document.getElementById('filtro-reporte-cajero');
    if (select) select.value = String(valor);
    actualizarSeleccionTarjetasCajeros();
    cargarReporteBoletas();
}

/** Cualquier cambio de filtro vuelve a la pagina 0. */
function filtrarReporteBoletas() {
    paginaReporteBoletas = 0;
    cargarReporteBoletas();
}

/**
 * Botones Anterior / Siguiente. Se arman sobre los datos que devuelve la
 * pagina ({primera, ultima, totalPaginas}) en vez de suponer que hay 25
 * registros: con el filtro puesto, la ultima pagina tiene menos filas y un
 * "siguiente" a ciegas llevaria a una pantalla vacia.
 *
 * Recibe un callback con el numero de pagina destino en vez de capturar la
 * variable de pagina en una copia local: si la closure modificara la copia,
 * el contador avanzaria pero la recarga siempre pediria la misma pagina.
 */
function pintarControlesPaginacion(prefijo, data, irAPagina, unidad = 'boletas') {
    const info = document.getElementById(`label-paginacion-${prefijo}`);
    const btnPrev = document.getElementById(`btn-pag-${prefijo}-anterior`);
    const btnNext = document.getElementById(`btn-pag-${prefijo}-siguiente`);
    const indicador = document.getElementById(`pagina-${prefijo}-actual`);

    const total = data.totalElementos || 0;
    const tamano = data.tamano || TAMANO_PAGINA_VENTAS;
    const totalPaginas = Math.max(1, data.totalPaginas || 1);
    const paginaActual = data.pagina || 0;
    const desde = total === 0 ? 0 : paginaActual * tamano + 1;
    const hasta = Math.min((paginaActual + 1) * tamano, total);

    if (info) info.innerText = `Mostrando ${desde} a ${hasta} de ${total} ${unidad}`;
    if (indicador) indicador.innerText = `Página ${paginaActual + 1} de ${totalPaginas}`;

    if (btnPrev) {
        btnPrev.disabled = data.primera !== false;
        btnPrev.onclick = () => irAPagina(Math.max(0, paginaActual - 1));
    }
    if (btnNext) {
        btnNext.disabled = data.ultima !== false;
        btnNext.onclick = () => irAPagina(Math.min(totalPaginas - 1, paginaActual + 1));
    }
}

function resumenMovilBoleta(boleta) {
    return `<span class="sales-mobile-meta">${esc(formatearFecha(boleta.fechaVenta))} · ${fmtDinero(boleta.total)}</span>`;
}

function botonDetalleBoleta(boleta, gestionar = false) {
    const boton = document.createElement('button');
    boton.type = 'button'; boton.className = 'table-row-action';
    boton.dataset.ventaId = String(boleta.idVenta);
    boton.setAttribute('aria-label', `Ver detalle completo de ${boleta.numeroComprobante || 'la venta'}`);
    boton.title = 'Ver detalle completo';
    boton.innerHTML = '<i class="fa-solid fa-eye" aria-hidden="true"></i>';
    boton.onclick = () => gestionar ? seleccionarBoletaParaDetalle(boleta.idVenta) : verDetalleBoleta(boleta.idVenta);
    return boton;
}

function renderizarTablaReporteBoletas(lista, datosPaginacion, cajeroSeleccionado) {
    const tbody = document.getElementById('tabla-reporte-boletas-body');
    const tituloEl = document.getElementById('titulo-caja-seleccionada');
    const subtituloEl = document.getElementById('subtitulo-cajero-seleccionado');

    let nombreCaja = 'TODOS LOS CAJEROS';
    if (cajeroSeleccionado !== 'TODOS') {
        const c = cajerosDisponiblesLista.find(x => String(x.idUsuario) === String(cajeroSeleccionado));
        nombreCaja = c ? c.nombreCompleto.toUpperCase() : 'CAJERO';
    }

    if (tituloEl) tituloEl.innerText = `DETALLE DE BOLETAS - ${nombreCaja}`;
    if (subtituloEl) {
        subtituloEl.innerText = cajeroSeleccionado === 'TODOS'
            ? 'Visualizando todas las boletas emitidas'
            : `Ventas de ${nombreCaja}`;
    }

    if (!tbody) return;
    tbody.innerHTML = '';

    if (!lista || lista.length === 0) {
        tbody.innerHTML = '<tr><td colspan="7" style="text-align:center; padding: 25px; color: var(--text-muted);">No se encontraron boletas con los filtros seleccionados.</td></tr>';
        return;
    }

    lista.forEach(b => {
        // b.metodoPago es el codigo canonico (punto 7); metodoPagoNombre es la
        // etiqueta. Se muestra la etiqueta: el codigo es para agrupar, no para
        // que lo lea un humano.
        const metodo = b.metodoPagoNombre || b.metodoPago;
        const tr = document.createElement('tr');
        tr.innerHTML = `
            <td><strong>${esc(b.numeroComprobante || 'B001-000000')}</strong>${resumenMovilBoleta(b)}</td>
            <td>${esc(formatearFecha(b.fechaVenta))}</td>
            <td>${esc(formatearHora(b.fechaVenta))}</td>
            <td>${esc(metodo)}</td>
            <td><strong>S/ ${parseFloat(b.total).toFixed(2)}</strong></td>
            <td>${badgeEstadoComprobante(b.estado)}</td>
            <td class="sale-detail-action"></td>
        `;
        tr.querySelector('.sale-detail-action').append(botonDetalleBoleta(b));
        tbody.appendChild(tr);
    });
}

let solicitudDetalleBoleta = null;
let focoDetalleBoleta = null;

let operacionBoletaEnCurso = false;

function cerrarDetalleBoleta() {
    if (operacionBoletaEnCurso) return;
    solicitudDetalleBoleta?.abort();
    document.getElementById('sale-detail-dialog')?.close();
}

function restaurarFocoDetalleBoleta() {
    const detalle = document.getElementById('sale-detail-dialog');
    if (!detalle || detalle.open) return;
    const boton = [...document.querySelectorAll('button[data-venta-id]')].find(el =>
        el.dataset.ventaId === detalle.dataset.ventaId);
    const foco = focoDetalleBoleta?.isConnected ? focoDetalleBoleta : boton;
    foco?.focus({ preventScroll: true });
}

async function verDetalleBoleta(id) {
    const dialogo = document.getElementById('sale-detail-dialog');
    const contenido = document.getElementById('sale-detail-content');
    if (!dialogo || !contenido) return;
    if (operacionBoletaEnCurso) return;
    const acciones = document.getElementById('sale-detail-management');
    if (acciones) { acciones.hidden = true; boletaSeleccionadaAnulacion = null; }
    solicitudDetalleBoleta?.abort();
    const solicitud = new AbortController();
    solicitudDetalleBoleta = solicitud;
    contenido.textContent = 'Cargando los detalles guardados de la venta…';
    contenido.setAttribute('aria-busy', 'true');
    if (!dialogo.open) {
        focoDetalleBoleta = document.activeElement;
        dialogo.dataset.ventaId = String(id);
        dialogo.showModal();
    }
    document.getElementById('sale-detail-title').focus({ preventScroll: true });
    try {
        const response = await apiFetch(`/ventas/${encodeURIComponent(id)}`, { signal: solicitud.signal });
        const venta = await response.json();
        if (!response.ok) throw new Error(venta.message || 'No se pudo cargar el detalle de esta venta.');
        if (solicitud.signal.aborted || !dialogo.open) return;
        const dato = (etiqueta, valor) => `<div><dt>${etiqueta}</dt><dd>${esc(valor ?? '—')}</dd></div>`;
        const detalles = Array.isArray(venta.detalles) ? venta.detalles : [];
        const tipoComprobante = { BOLETA_SIMPLE: 'Boleta simple', BOLETA_DNI: 'Boleta con DNI', FACTURA: 'Factura' }[venta.tipoComprobante] || venta.tipoComprobante;
        contenido.innerHTML =
            `<div class="sale-detail-heading"><strong>${esc(venta.numeroComprobante || `Venta ${venta.idVenta}`)}</strong>${badgeEstadoComprobante(venta.estado)}</div>` +
            `<dl class="sale-detail-meta">` +
                dato('Fecha y hora', `${formatearFecha(venta.fechaVenta)} · ${formatearHora(venta.fechaVenta)}`) +
                dato('Cajero', venta.cajeroNombre) + dato('Caja', boletasLista.find(b => String(b.idVenta) === String(venta.idVenta))?.cajaNombre || 'Caja general') + dato('Cliente', venta.clienteNombre) +
                dato('Documento', venta.clienteDocumento || 'Sin documento') +
                dato('Comprobante', tipoComprobante) + dato('Referencia de la venta', venta.idVenta) +
            `</dl><h4>Productos de la venta</h4><ol class="sale-detail-items">` + detalles.map(item =>
                `<li><div class="sale-detail-product"><strong>${esc(item.cantidad)} × ${esc(item.nombreProducto)}</strong><strong>${fmtDinero(item.subtotal)}</strong></div>` +
                    `<p class="sale-detail-unit">Producto #${esc(item.idProducto)} · Precio unitario: ${fmtDinero(item.precioUnitario)}</p>` +
                    `<dl class="sale-detail-options">` + dato('Toppings', item.toppings || 'Sin toppings') +
                    dato('Frutas', item.frutas || 'Sin frutas') + dato('Sabores', item.sabores || 'Sin sabores') + dato('Notas', item.notas || 'Sin notas registradas') + `</dl></li>`
            ).join('') + `</ol>` + (detalles.length ? '' : '<p>Esta venta no tiene productos registrados.</p>') +
            `<div class="sale-detail-summary"><dl>` +
                dato('Operación gravada', fmtDinero(venta.subtotal)) + dato('IGV', fmtDinero(venta.igv)) +
                dato('Total de la venta', fmtDinero(venta.total)) + dato('Método de pago', venta.metodoPago) +
                dato('Efectivo aplicado', fmtDinero(venta.montoEfectivo)) + dato('Pago digital aplicado', fmtDinero(venta.montoDigital)) +
                dato('Vuelto', fmtDinero(venta.vuelto)) + `</dl></div>`;
        if (acciones) prepararAccionesBoleta(venta);
    } catch (error) {
        if (solicitud.signal.aborted) return;
        contenido.replaceChildren();
        const mensaje = document.createElement('p');
        mensaje.className = 'inline-form-error';
        mensaje.setAttribute('role', 'alert');
        mensaje.textContent = error.message || 'No se pudo conectar. Inténtalo de nuevo.';
        const reintentar = document.createElement('button');
        reintentar.type = 'button'; reintentar.className = 'btn-table-action'; reintentar.textContent = 'Reintentar';
        reintentar.onclick = () => verDetalleBoleta(id);
        contenido.append(mensaje, reintentar);
    } finally {
        if (solicitudDetalleBoleta === solicitud) contenido.setAttribute('aria-busy', 'false');
    }
}

function formatearFecha(iso) {
    const d = new Date(iso);
    return isNaN(d) ? '—' : d.toLocaleDateString('es-PE');
}

function formatearHora(iso) {
    const d = new Date(iso);
    return isNaN(d) ? '—' : d.toLocaleTimeString('es-PE');
}

// =====================================================
// 6. MÓDULO DE ANULACIÓN O MODIFICACIÓN DE BOLETAS
// =====================================================
// Al igual que el reporte, pagina en el servidor: antes descargaba todas las
// ventas para elegir una y anularla.

async function cargarAnulacionBoletas() {
    await cargarCajerosParaFiltros();
    poblarSelectsAnulacion();
    await aplicarFiltrosAnulacion();
}

function filtrosAnulacion() {
    const texto = document.getElementById('filtro-anulacion-buscar')?.value || '';
    const caja = document.getElementById('filtro-anulacion-caja')?.value || 'TODAS';
    const cajero = document.getElementById('filtro-anulacion-cajero')?.value || 'TODOS';

    return {
        pagina: paginaAnulacion,
        tamano: TAMANO_PAGINA_VENTAS,
        buscar: texto,
        // Ambos selects identifican el mismo id_usuario. La selección activa
        // se normaliza en filtrarAnulacionBoletas para evitar filtros opuestos.
        caja: caja !== 'TODAS' ? caja : (cajero !== 'TODOS' ? cajero : ''),
        desde: document.getElementById('filtro-anulacion-f-inicio')?.value || '',
        hasta: document.getElementById('filtro-anulacion-f-fin')?.value || ''
    };
}

async function aplicarFiltrosAnulacion() {
    try {
        const res = await apiFetch(`/ventas?${queryVentas(filtrosAnulacion())}`);
        if (!res.ok) throw new Error('Error cargando ventas');
        const data = await res.json();
        boletasLista = data.contenido || [];
        paginaAnulacion = data.pagina || 0;

        renderizarTablaAnulacion(boletasLista);
        pintarControlesPaginacion('anulacion', data, (nuevaPagina) => {
            paginaAnulacion = nuevaPagina;
            aplicarFiltrosAnulacion();
        });

    } catch (err) {
        console.error('No se pudieron cargar las boletas para anulación:', err);
        boletasLista = [];
        const tbody = document.getElementById('tabla-anulacion-body');
        if (tbody) {
            tbody.innerHTML = '<tr><td colspan="9" style="text-align:center; padding: 20px; color: #b91c1c;">No se pudieron cargar las boletas. Verifica tu sesión o conexión e inténtalo de nuevo.</td></tr>';
        }
        const info = document.getElementById('label-paginacion-anulacion');
        if (info) info.innerText = 'No se pudo cargar la lista';
        const anterior = document.getElementById('btn-pag-anulacion-anterior');
        const siguiente = document.getElementById('btn-pag-anulacion-siguiente');
        if (anterior) anterior.disabled = true;
        if (siguiente) siguiente.disabled = true;
        limpiarDetalleAnulacionBoleta('No se pudo cargar el detalle.');
    }
}

/** Cualquier cambio de filtro vuelve a la pagina 0. */
function filtrarAnulacionBoletas(origen) {
    const selectCaja = document.getElementById('filtro-anulacion-caja');
    const selectCajero = document.getElementById('filtro-anulacion-cajero');
    if (origen === 'caja' && selectCajero) selectCajero.value = 'TODOS';
    if (origen === 'cajero' && selectCaja) selectCaja.value = 'TODAS';
    paginaAnulacion = 0;
    aplicarFiltrosAnulacion();
}

function renderizarTablaAnulacion(lista) {
    const tbody = document.getElementById('tabla-anulacion-body');
    if (!tbody) return;

    tbody.innerHTML = '';
    if (!lista || lista.length === 0) {
        tbody.innerHTML = '<tr><td colspan="9" style="text-align:center; padding: 20px; color: var(--text-muted);">No hay boletas registradas.</td></tr>';
        return;
    }

    lista.forEach(b => {
        const metodo = b.metodoPagoNombre || b.metodoPago;
        const tr = document.createElement('tr');
        tr.innerHTML = `
            <td><strong>${esc(b.numeroComprobante || 'B001-000000')}</strong>${resumenMovilBoleta(b)}</td>
            <td>${esc(formatearFecha(b.fechaVenta))}</td>
            <td>${esc(formatearHora(b.fechaVenta))}</td>
            <td>${esc(b.cajaNombre || 'Caja general')}</td>
            <td>${esc(b.cajeroNombre || 'Sin asignar')}</td>
            <td>${esc(b.metodoPagoNombre || b.metodoPago)}</td>
            <td><strong>S/ ${parseFloat(b.total).toFixed(2)}</strong></td>
            <td>${badgeEstadoComprobante(b.estado)}</td>
            <td class="sale-detail-action"></td>
        `;
        tr.querySelector('.sale-detail-action').append(botonDetalleBoleta(b, true));
        tbody.appendChild(tr);
    });
}

function limpiarDetalleAnulacionBoleta() {
    boletaSeleccionadaAnulacion = null;
    const acciones = document.getElementById('sale-detail-management');
    if (acciones) acciones.hidden = true;
}

function seleccionarBoletaParaDetalle(idVenta) {
    return verDetalleBoleta(typeof idVenta === 'object' ? idVenta.idVenta : idVenta);
}

function prepararAccionesBoleta(venta) {
    boletaSeleccionadaAnulacion = venta;
    const anulada = venta.estado === 'ANULADA';
    const acciones = document.getElementById('sale-detail-management');
    acciones.hidden = false;
    acciones.querySelectorAll('button, select, textarea').forEach(el => el.disabled = anulada);
    document.getElementById('anular-ya-anulada-aviso').style.display = anulada ? 'block' : 'none';
    document.getElementById('mod-metodo-actual-badge').textContent = venta.metodoPago;
    const selectorMetodo = document.getElementById('mod-nuevo-metodo-select');
    selectorMetodo.value = Array.from(selectorMetodo.options)
        .find(opcion => opcion.value === venta.metodoPago || opcion.textContent === venta.metodoPago)?.value || '';
    document.getElementById('anular-nro-boleta-label').textContent = venta.numeroComprobante;
    document.getElementById('input-motivo-anulacion').value = '';
    cambiarTabAccion('modificar');
}

function bloquearAccionesBoleta(bloquear) {
    operacionBoletaEnCurso = bloquear;
    document.querySelectorAll('#sale-detail-management button, #sale-detail-management select, #sale-detail-management textarea, #sale-detail-dialog .btn-close-modal, #sale-detail-dialog .modal-footer > button').forEach(el => el.disabled = bloquear);
}

function cambiarTabAccion(tab) {
    const btnMod = document.getElementById('tab-btn-modificar');
    const btnEli = document.getElementById('tab-btn-eliminar');
    const contentMod = document.getElementById('tab-content-modificar');
    const contentEli = document.getElementById('tab-content-eliminar');
    btnMod.setAttribute('aria-pressed', String(tab === 'modificar'));
    btnEli.setAttribute('aria-pressed', String(tab === 'eliminar'));

    if (tab === 'modificar') {
        btnMod.classList.add('active');
        btnEli.classList.remove('active');
        contentMod.classList.add('active');
        contentEli.classList.remove('active');
    } else {
        btnEli.classList.add('active');
        btnMod.classList.remove('active');
        contentEli.classList.add('active');
        contentMod.classList.remove('active');
    }
}

async function guardarModificacionMetodoPago() {
    if (operacionBoletaEnCurso) return;
    if (!boletaSeleccionadaAnulacion) {
        mostrarAvisoSistema('Selecciona una boleta primero.');
        return;
    }

    const nuevoMetodo = document.getElementById('mod-nuevo-metodo-select').value;
    const boleta = boletaSeleccionadaAnulacion;
    bloquearAccionesBoleta(true);
    try {
        const res = await apiFetch(`/ventas/${boleta.idVenta}/metodo-pago`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ metodoPago: nuevoMetodo })
        });
        const data = await res.json();

        if (res.ok && data.success) {
            bloquearAccionesBoleta(false);
            await verDetalleBoleta(boleta.idVenta);
            mostrarAvisoSistema(`Método de pago de la boleta ${boleta.numeroComprobante} actualizado correctamente.`, 'success');
            await aplicarFiltrosAnulacion();
        } else {
            mostrarAvisoSistema(data.message || 'No se pudo modificar el método de pago.');
        }
    } catch (err) {
        errorControlado(err, 'Error de conexión al modificar el método de pago.');
    } finally {
        bloquearAccionesBoleta(false);
    }
}

async function ejecutarAnulacionBoleta() {
    if (operacionBoletaEnCurso) return;
    if (!boletaSeleccionadaAnulacion) {
        mostrarAvisoSistema('Selecciona una boleta primero.');
        return;
    }

    if ((boletaSeleccionadaAnulacion.estado || '').toUpperCase() === 'ANULADA') {
        mostrarAvisoSistema('Esta boleta ya está anulada. No se puede anular dos veces.');
        return;
    }

    // El motivo es obligatorio: sin el, la anulación no queda respaldada y
    // el backend la rechaza. Se lee del textarea del panel para que el admin
    // pueda escribir varios detalles, no solo una linea.
    const campoMotivo = document.getElementById('input-motivo-anulacion');
    const motivo = campoMotivo?.value || '';
    if (!motivo.trim()) {
        mostrarAvisoSistema('El motivo es obligatorio para anular una boleta.');
        campoMotivo?.focus();
        return;
    }

    const boleta = boletaSeleccionadaAnulacion;
    if (!await confirmarSistema(`¿Anular la boleta ${boleta.numeroComprobante}? Se devolverá el stock al inventario y se conservará el registro de la anulación.`, 'Anular boleta')) return;
    bloquearAccionesBoleta(true);
    try {
        // Punto 11: ya no se borra la venta. El backend la marca ANULADA,
        // emite la nota de crédito y devuelve el stock. Antes esto era un
        // DELETE que desaparecía el comprobante de la base de datos.
        const res = await apiFetch(`/ventas/${boleta.idVenta}/anular`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ motivo: motivo.trim() })
        });
        const data = await res.json();

        if (res.ok && data.success) {
            bloquearAccionesBoleta(false);
            cerrarDetalleBoleta();
            mostrarAvisoSistema(`Boleta ${data.numeroComprobante} anulada correctamente.\nNota de crédito: ${data.numeroNotaCredito}. Monto: ${fmtDinero(data.totalAnulado)}.`, 'success');
            if (campoMotivo) campoMotivo.value = '';
            await aplicarFiltrosAnulacion();
            restaurarFocoDetalleBoleta();
        } else {
            mostrarAvisoSistema(data.message || 'No se pudo anular la boleta.');
        }
    } catch (err) {
        errorControlado(err, 'Error de conexión al anular la boleta.');
    } finally {
        bloquearAccionesBoleta(false);
    }
}

// =====================================================
// 7. MÓDULO DE REPORTE GENERAL (CON COMBINADO)
// =====================================================
// Punto 4: los KPIs, el reparto por metodo de pago y el grafico salen de tres
// endpoints de agregacion. Antes se bajaba la tabla entera y se sumaba en el
// navegador, asi que el reporte solo era correcto mientras la lista completa
// entrara en memoria. Ademas el reparto se hacia con indexOf sobre metodoPago
// en mayusculas: 'Efectivo' y 'EFECTIVO' caian en ramas distintas.

let revisionReporteGeneral = 0;

async function cargarReporteGeneral() {
    const revision = ++revisionReporteGeneral;
    set('catalogo-dashboard-estado', 'Cargando estadísticas…');
    document.getElementById('catalogo-dashboard-datos').hidden = true;
    const filtros = {
        pagina: paginaReporteGeneral,
        tamano: TAMANO_PAGINA_VENTAS,
        desde: document.getElementById('rep-gen-f-inicio')?.value || '',
        hasta: document.getElementById('rep-gen-f-fin')?.value || '',
        estado: 'EMITIDA'
    };
    const rango = queryVentas({ desde: filtros.desde, hasta: filtros.hasta });
    const qs = queryVentas(filtros);
    const fechaLocal = (fecha) => [
        fecha.getFullYear(),
        String(fecha.getMonth() + 1).padStart(2, '0'),
        String(fecha.getDate()).padStart(2, '0')
    ].join('-');
    const inicioGrafico = new Date();
    inicioGrafico.setDate(inicioGrafico.getDate() - 6);
    const rangoGrafico = queryVentas({
        desde: fechaLocal(inicioGrafico),
        hasta: fechaLocal(new Date())
    });

    try {
        // Las cuatro peticiones en paralelo: son independientes y el reporte
        // no tiene sentido con la mitad de los datos.
        const [resLista, resResumen, resPorDia, resPorMetodo, resCatalogo] = await Promise.all([
            apiFetch(`/ventas?${qs}`),
            apiFetch(`/ventas/resumen?${rango}`),
            apiFetch(`/ventas/por-dia?${rangoGrafico}`),
            apiFetch(`/ventas/por-metodo-pago?${rango}`),
            apiFetch(`/ventas/rendimiento-productos?${rango}`)
        ]);
        if (revision !== revisionReporteGeneral) return;

        if (!resLista.ok) {
            throw new Error(`No se pudo cargar la tabla de ventas (HTTP ${resLista.status}).`);
        }
        const data = await resLista.json();
        boletasLista = data.contenido || [];
        paginaReporteGeneral = data.pagina || 0;

        renderizarTablaReporteGeneral(boletasLista);
        pintarControlesPaginacion('reporte-general', data, (nuevaPagina) => {
            paginaReporteGeneral = nuevaPagina;
            cargarReporteGeneral();
        });

        pintarKpisReporteGeneral(resResumen.ok ? await resResumen.json() : null);
        if (resPorDia.ok) {
            renderizarGraficoVentasDinamico(await resPorDia.json());
        } else {
            const chart = document.getElementById('chart-dynamic-wrapper');
            if (chart) {
                chart.textContent = `No se pudo cargar la gráfica (HTTP ${resPorDia.status}).`;
            }
            console.error(`Error HTTP ${resPorDia.status} al cargar las ventas diarias.`);
        }
        pintarMetodosReporteGeneral(resPorMetodo.ok ? await resPorMetodo.json() : []);
        if (resCatalogo.ok) pintarRendimientoCatalogo(await resCatalogo.json());
        else set('catalogo-dashboard-estado', 'No se pudieron cargar las estadísticas de productos. Revisa las fechas o vuelve a aplicar el filtro.');
    } catch (err) {
        if (revision !== revisionReporteGeneral) return;
        set('catalogo-dashboard-estado', 'No se pudieron cargar las estadísticas. Vuelve a aplicar el filtro para reintentar.');
        console.warn('Error al cargar el reporte general:', err);
        boletasLista = [];
        const tbody = document.getElementById('tabla-reporte-general-body');
        if (tbody) {
            const mensaje = err instanceof Error ? err.message : 'Error inesperado al cargar las ventas.';
            tbody.innerHTML = `<tr><td colspan="8" style="text-align:center; padding: 25px; color: #b42318;">${esc(mensaje)}</td></tr>`;
        }
    }
}

function pintarRendimientoCatalogo(data) {
    const categorias = Array.isArray(data.categorias) ? data.categorias : [];
    const productos = Array.isArray(data.productos) ? data.productos : [];
    const datos = document.getElementById('catalogo-dashboard-datos');
    if (!categorias.length) {
        datos.hidden = true;
        set('catalogo-dashboard-estado', 'No hay ventas emitidas para el período seleccionado.');
        return;
    }
    set('catalogo-dashboard-estado', ''); datos.hidden = false;
    const numero = n => Number(n || 0).toLocaleString('es-PE');
    const porcentaje = n => Number(n || 0).toLocaleString('es-PE', { maximumFractionDigits: 1 }) + ' %';
    const unidades = n => `${numero(n)} ${Number(n) === 1 ? 'unidad' : 'unidades'}`;
    const barra = n => Math.min(100, Math.max(0, Number(n) || 0));
    set('catalogo-ingresos', fmtDinero(data.totalVentas));
    set('catalogo-unidades', numero(data.totalUnidades));
    set('catalogo-producto-lider', productos[0]?.nombre || '—');
    set('catalogo-producto-cuota', productos[0] ? `${unidades(productos[0].unidades)} · ${porcentaje(productos[0].porcentajeUnidades)} del total de unidades` : '');
    const desde = document.getElementById('rep-gen-f-inicio').value;
    const hasta = document.getElementById('rep-gen-f-fin').value;
    set('catalogo-dashboard-periodo', desde || hasta ? `Período: ${desde || 'inicio'} a ${hasta || 'hoy'}` : 'Todas las ventas registradas. Puedes elegir un período con el filtro de fechas.');
    document.getElementById('catalogo-categorias').innerHTML = categorias.map(c => `<li><div class="catalogo-bar-label"><strong>${esc(c.nombre)}</strong><span>${fmtDinero(c.monto)}</span></div><div class="catalogo-bar-track" aria-hidden="true"><span style="width:${barra(c.porcentaje)}%"></span></div><small>${porcentaje(c.porcentaje)} de los ingresos · ${unidades(c.unidades)}</small></li>`).join('');
    document.getElementById('catalogo-productos').innerHTML = productos.map((p, i) => `<li><div class="catalogo-bar-label"><strong>${i + 1}. ${esc(p.nombre)}</strong><span>${numero(p.unidades)} uds.</span></div><div class="catalogo-bar-track" aria-hidden="true"><span style="width:${barra(p.porcentajeUnidades)}%"></span></div><small>${porcentaje(p.porcentajeUnidades)} de las unidades · ${fmtDinero(p.monto)} · ${porcentaje(p.porcentajeVentas)} de los ingresos</small></li>`).join('');
}

function pintarKpisReporteGeneral(resumen) {
    if (!resumen) return;
    const el = (id, valor) => {
        const e = document.getElementById(id);
        if (e) e.innerText = valor;
    };
    el('kpi-ventas-totales', fmtDinero(resumen.totalEmitido));
    el('kpi-total-boletas', resumen.boletasEmitidas);
    el('kpi-ticket-promedio', fmtDinero(resumen.ticketPromedio));
    el('kpi-total-anuladas', resumen.anuladas);
    el('kpi-efectivo', fmtDinero(resumen.efectivo));
    el('kpi-digital', fmtDinero(resumen.digital));
}

/**
 * Reparto por metodo de pago. Los datos vienen ya agrupados por el codigo
 * canonico, asi que no hay que clasificar nada aca: el servidor hizo el SUM.
 *
 * El HTML tiene cuatro casilleros fijos (efectivo, yape, combinado, pos), asi
 * que se rellenan por codigo. Plin y cualquier metodo nuevo que se agregue se
 * acumulan en "digital" en vez de perderse.
 */
function pintarMetodosReporteGeneral(lista) {
    const por = new Map((lista || []).map(m => [m.codigo, m]));

    const monto = (codigo) => {
        const m = por.get(codigo);
        return m ? parseFloat(m.total) : 0;
    };
    const digitos = (codigo) => {
        const m = por.get(codigo);
        return m ? parseFloat(m.porcentaje) : 0;
    };

    const codigosCombinados = ['EFECTIVO_COMBINADO', 'EFECTIVO_POS', 'EFECTIVO_YAPE'];
    const efectivo = monto('EFECTIVO');
    const yape = monto('YAPE');
    const combinado = codigosCombinados.reduce((total, codigo) => total + monto(codigo), 0);
    const porcentajeCombinado = codigosCombinados.reduce((total, codigo) => total + digitos(codigo), 0);
    const pos = monto('POS');
    const plin = monto('PLIN');

    const set = (idMonto, idPct, valor, pct) => {
        const m = document.getElementById(idMonto);
        const p = document.getElementById(idPct);
        if (m) m.innerText = fmtDinero(valor);
        if (p) p.innerText = `${pct.toFixed(2)}%`;
    };

    set('resumen-periodo-efectivo', 'pct-periodo-efectivo', efectivo, digitos('EFECTIVO'));
    set('resumen-periodo-yape', 'pct-periodo-yape', yape, digitos('YAPE'));
    set('resumen-periodo-combinado', 'pct-periodo-combinado', combinado, porcentajeCombinado);
    set('resumen-periodo-pos', 'pct-periodo-pos', pos + plin, digitos('POS') + digitos('PLIN'));

    const total = lista ? lista.reduce((acc, m) => acc + parseFloat(m.total), 0) : 0;
    const elTotal = document.getElementById('resumen-periodo-total');
    if (elTotal) elTotal.innerText = fmtDinero(total);
}

/**
 * Grafico de los ultimos 7 dias. Los totales por dia llegan ya sumados de
 * /ventas/por-dia; antes se armaba el array de dias vacio y se recorria la
 * tabla completa buscando cada fecha, lo cual solo cubria 7 dias de todos
 * modos y con el filtro puesto mostraba una recta en cero.
 */
function renderizarGraficoVentasDinamico(serieDia) {
    const wrapper = document.getElementById('chart-dynamic-wrapper');
    if (!wrapper) return;

    const dias = [];
    const hoy = new Date();
    const nombresDias = ['Dom', 'Lun', 'Mar', 'Mié', 'Jue', 'Vie', 'Sáb'];

    for (let i = 6; i >= 0; i--) {
        const d = new Date();
        d.setDate(hoy.getDate() - i);
        const yyyy = d.getFullYear();
        const mm = String(d.getMonth() + 1).padStart(2, '0');
        const dd = String(d.getDate()).padStart(2, '0');

        dias.push({
            fechaStr: `${yyyy}-${mm}-${dd}`,
            diaMes: `${dd}/${mm}`,
            nombreDia: nombresDias[d.getDay()],
            totalVenta: 0,
            cantidadVentas: 0
        });
    }

    // Index por fecha para no buscar linealmente por cada dia.
    const porFecha = new Map((serieDia || []).map(p => [String(p.fecha), {
        total: parseFloat(p.total) || 0,
        cantidad: parseInt(p.cantidad, 10) || 0
    }]));
    dias.forEach(d => {
        const datoDia = porFecha.get(d.fechaStr);
        if (datoDia) {
            d.totalVenta = datoDia.total;
            d.cantidadVentas = datoDia.cantidad;
        }
    });

    const maxVenta = Math.max(...dias.map(d => d.totalVenta), 20);
    const maxEscala = Math.ceil((maxVenta * 1.25) / 10) * 10;

    const xPositions = [70, 160, 250, 340, 430, 520, 610];
    const yBaseline = 210;
    const yTop = 40;
    const chartHeight = yBaseline - yTop;

    const points = dias.map((d, idx) => {
        const x = xPositions[idx];
        const y = yBaseline - ((d.totalVenta / maxEscala) * chartHeight);
        return {
            x,
            y,
            total: d.totalVenta,
            cantidad: d.cantidadVentas,
            diaMes: d.diaMes,
            nombreDia: d.nombreDia
        };
    });

    const polylineStr = points.map(p => `${p.x},${p.y.toFixed(1)}`).join(' ');
    const polygonStr = `${points[0].x},${yBaseline} ` + polylineStr + ` ${points[points.length - 1].x},${yBaseline}`;

    let svgHtml = `
        <svg class="sales-chart-svg" viewBox="0 0 700 240" preserveAspectRatio="none">
            <defs>
                <linearGradient id="pinkGradient" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stop-color="#e2006a" stop-opacity="0.25"/>
                    <stop offset="100%" stop-color="#e2006a" stop-opacity="0.0"/>
                </linearGradient>
            </defs>

            <line x1="40" y1="${yTop}" x2="680" y2="${yTop}" stroke="#fce4ec" stroke-dasharray="3,3" />
            <line x1="40" y1="${yTop + (chartHeight * 0.33)}" x2="680" y2="${yTop + (chartHeight * 0.33)}" stroke="#fce4ec" stroke-dasharray="3,3" />
            <line x1="40" y1="${yTop + (chartHeight * 0.66)}" x2="680" y2="${yTop + (chartHeight * 0.66)}" stroke="#fce4ec" stroke-dasharray="3,3" />
            <line x1="40" y1="${yBaseline}" x2="680" y2="${yBaseline}" stroke="#fce4ec" />

            <text x="5" y="${yTop + 4}" class="svg-axis-label">S/ ${maxEscala.toFixed(0)}</text>
            <text x="5" y="${yTop + (chartHeight * 0.5) + 4}" class="svg-axis-label">S/ ${(maxEscala / 2).toFixed(0)}</text>
            <text x="15" y="${yBaseline + 4}" class="svg-axis-label">S/ 0</text>

            <polygon points="${polygonStr}" fill="url(#pinkGradient)" />
            <polyline points="${polylineStr}" fill="none" stroke="#e2006a" stroke-width="3" stroke-linecap="round" stroke-linejoin="round" />

            <g class="chart-point-group">
    `;

    points.forEach(p => {
        svgHtml += `
            <circle cx="${p.x}" cy="${p.y.toFixed(1)}" r="5" fill="#e2006a">
                <title>${p.cantidad} compras · S/ ${p.total.toFixed(2)} · ${p.diaMes}</title>
            </circle>
            <text x="${p.x}" y="${(p.y - 10).toFixed(1)}" text-anchor="middle" class="svg-point-val">S/ ${p.total.toFixed(2)}</text>
        `;
    });

    svgHtml += `
            </g>
        </svg>
        <div class="chart-x-axis">
    `;

    dias.forEach(d => {
        svgHtml += `<div><span>${d.diaMes}</span><small>${d.nombreDia}</small></div>`;
    });

    svgHtml += `</div>`;
    wrapper.innerHTML = svgHtml;
}

/** Cualquier cambio de filtro vuelve a la pagina 0. */
function limpiarErrorFechasReporte() {
    document.getElementById('rep-gen-f-fin').setCustomValidity('');
    const error = document.getElementById('rep-gen-fechas-error');
    error.textContent = ''; error.hidden = true;
}

function filtrarReporteGeneral(event) {
    event?.preventDefault();
    limpiarErrorFechasReporte();
    const desde = document.getElementById('rep-gen-f-inicio');
    const hasta = document.getElementById('rep-gen-f-fin');
    if (desde.value && hasta.value && desde.value > hasta.value) {
        const mensaje = 'La fecha final debe ser igual o posterior a la fecha inicial.';
        hasta.setCustomValidity(mensaje);
        const error = document.getElementById('rep-gen-fechas-error');
        error.textContent = mensaje; error.hidden = false;
        hasta.focus();
        return;
    }
    paginaReporteGeneral = 0;
    cargarReporteGeneral();
}

function limpiarFechasReporteGeneral() {
    document.getElementById('rep-gen-f-inicio').value = '';
    document.getElementById('rep-gen-f-fin').value = '';
    filtrarReporteGeneral();
}

function renderizarTablaReporteGeneral(lista) {
    const tbody = document.getElementById('tabla-reporte-general-body');
    if (!tbody) return;

    tbody.innerHTML = '';
    if (!lista || lista.length === 0) {
        tbody.innerHTML = '<tr><td colspan="8" style="text-align:center; padding: 25px; color: var(--text-muted);">No hay ventas registradas.</td></tr>';
        return;
    }

    lista.forEach(b => {
        const tr = document.createElement('tr');
        tr.innerHTML = `
            <td><strong>${esc(b.numeroComprobante || 'B001-000000')}</strong>${resumenMovilBoleta(b)}</td>
            <td>${esc(formatearFecha(b.fechaVenta))}</td>
            <td>${esc(b.cajeroNombre || 'Sin asignar')}</td>
            <td>${esc(b.cajaNombre || 'Sin asignar')}</td>
            <td>${esc(b.metodoPagoNombre || b.metodoPago)}</td>
            <td><strong>S/ ${parseFloat(b.total).toFixed(2)}</strong></td>
            <td>${badgeEstadoComprobante(b.estado)}</td>
            <td class="sale-detail-action"></td>
        `;
        tr.querySelector('.sale-detail-action').append(botonDetalleBoleta(b));
        tbody.appendChild(tr);
    });
}

// =====================================================
// 8. MÓDULO DE CREACIÓN Y GESTIÓN DE USUARIOS CAJEROS
// =====================================================
async function cargarUsuariosCajeros() {
    try {
        const res = await apiFetch(`/usuarios`);
        if (!res.ok) throw new Error('Error al cargar usuarios');
        usuariosCajerosLista = await res.json();
    } catch (err) {
        usuariosCajerosLista = [];
    }

    filtrarUsuariosCajeros(false);
}

let paginaUsuariosCajeros = 0;

function filtrarUsuariosCajeros(reiniciar = true) {
    if (reiniciar) paginaUsuariosCajeros = 0;
    const texto = (document.getElementById('filtro-buscar-usuario')?.value || '').toLowerCase();
    const estado = document.getElementById('filtro-estado-usuario')?.value || 'TODOS';

    const cajeros = usuariosCajerosLista.filter(u => (u.rol || '').toUpperCase() === 'CAJERO');

    const filtrados = cajeros.filter(u => {
        const matchTexto = (u.nombreCompleto || '').toLowerCase().includes(texto) || (u.usuarioLogin || '').toLowerCase().includes(texto);
        const matchEstado = (estado === 'TODOS') || ((u.estado || 'Activo').toLowerCase() === estado.toLowerCase());
        return matchTexto && matchEstado;
    });

    const totalPaginas = Math.max(1, Math.ceil(filtrados.length / TAMANO_PAGINA_VENTAS));
    paginaUsuariosCajeros = Math.min(paginaUsuariosCajeros, totalPaginas - 1);
    const inicio = paginaUsuariosCajeros * TAMANO_PAGINA_VENTAS;
    renderizarTablaUsuariosCajeros(filtrados.slice(inicio, inicio + TAMANO_PAGINA_VENTAS), inicio);
    pintarControlesPaginacion('usuarios', {
        pagina: paginaUsuariosCajeros, tamano: TAMANO_PAGINA_VENTAS,
        totalElementos: filtrados.length, totalPaginas,
        primera: paginaUsuariosCajeros === 0, ultima: paginaUsuariosCajeros === totalPaginas - 1
    }, pagina => { paginaUsuariosCajeros = pagina; filtrarUsuariosCajeros(false); }, 'usuarios');
}

function renderizarTablaUsuariosCajeros(lista, inicio = 0) {
    const tbody = document.getElementById('tabla-usuarios-cajeros-body');
    if (!tbody) return;

    tbody.innerHTML = '';
    if (lista.length === 0) {
        tbody.innerHTML = '<tr><td colspan="7" style="text-align:center; padding: 20px; color: var(--text-muted);">No hay cajeros registrados en PostgreSQL.</td></tr>';
        return;
    }

    lista.forEach((u, idx) => {
        const fechaObj = new Date(u.fechaCreacion);
        const fStr = isNaN(fechaObj) ? '26/08/2026' : fechaObj.toLocaleDateString('es-PE');
        const hStr = isNaN(fechaObj) ? '08:30 AM' : fechaObj.toLocaleTimeString('es-PE', { hour: '2-digit', minute: '2-digit' });
        const esActivo = (u.estado || 'Activo').toLowerCase() === 'activo';

        const tr = document.createElement('tr');
        tr.innerHTML = `
            <td><strong>${inicio + idx + 1}</strong></td>
            <td><strong>${esc(u.nombreCompleto)}</strong></td>
            <td>${esc(u.usuarioLogin)}</td>
            <td>${esc(u.rol || 'Cajero')}</td>
            <td>${esc(fStr)} ${esc(hStr)}</td>
            <td>
                <span class="status-badge ${esActivo ? 'disponible' : 'stock-bajo'}">
                    • ${esc(u.estado || 'Activo')}
                </span>
            </td>
            <td>
                <div class="usuario-fila-acciones"><button type="button" class="btn-eliminar-usuario-row btn-estado-usuario ${esActivo ? '' : 'reactivar'}" aria-label="${esActivo ? 'Desactivar' : 'Reactivar'} a ${esc(u.nombreCompleto)}" title="${esActivo ? 'Desactivar' : 'Reactivar'} cajero" onclick="cambiarEstadoCajero(${esc(u.idUsuario)})"><i class="fa-solid ${esActivo ? 'fa-user-slash' : 'fa-user-check'}" aria-hidden="true"></i> ${esActivo ? 'Desactivar' : 'Reactivar'}</button>
                <button type="button" class="btn-eliminar-usuario-row" aria-label="Eliminar usuario ${esc(u.nombreCompleto)}" title="Eliminar usuario" onclick="eliminarUsuarioCajero(${esc(u.idUsuario)})">
                    <i class="fa-regular fa-trash-can" aria-hidden="true"></i> Eliminar
                </button></div>
            </td>
        `;
        tbody.appendChild(tr);
    });

}

function enfocarFormularioUsuario() {
    const inputNombre = document.getElementById('cajero-nombre-completo');
    if (inputNombre) {
        inputNombre.focus();
        document.getElementById('card-form-usuario')?.scrollIntoView({ behavior: 'smooth' });
    }
}

function togglePasswordVisibility(inputId, icon) {
    const input = document.getElementById(inputId);
    if (!input) return;

    if (input.type === 'password') {
        input.type = 'text';
        icon.className = 'fa-regular fa-eye-slash toggle-pass-icon';
    } else {
        input.type = 'password';
        icon.className = 'fa-regular fa-eye toggle-pass-icon';
    }
}

async function guardarNuevoCajero(e) {
    e.preventDefault();
    const nombreCompleto = document.getElementById('cajero-nombre-completo').value.trim();
    const usuarioLogin = document.getElementById('cajero-usuario-login').value.trim();
    const password = document.getElementById('cajero-password').value.trim();

    if (!nombreCompleto || !usuarioLogin || !password) {
        mostrarAvisoSistema('Por favor completa todos los campos.');
        return;
    }

    const payload = {
        nombreCompleto: nombreCompleto,
        usuarioLogin: usuarioLogin,
        password: password
    };

    try {
        const res = await apiFetch(`/usuarios`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(payload)
        });
        const data = await res.json();

        if (data.success) {
            mostrarAvisoSistema(`¡Usuario cajero "${nombreCompleto}" creado con éxito!`);
            document.getElementById('form-crear-cajero').reset();
            cargarUsuariosCajeros();
        } else {
            mostrarAvisoSistema(data.message || 'Error al crear el usuario.');
        }
    } catch (err) {
        errorControlado(err, 'Error de conexión al crear el cajero.');
    }
}

async function cambiarMiPin(e) {
    e.preventDefault();
    const pinActual = document.getElementById('mi-pin-actual').value;
    const pinNuevo = document.getElementById('mi-pin-nuevo').value;
    if (!/^\d{4,10}$/.test(pinNuevo)) {
        mostrarAvisoSistema('El PIN nuevo debe tener entre 4 y 10 digitos.');
        return;
    }

    try {
        const res = await apiFetch(`/usuarios/mi-pin`, {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ pinActual, pinNuevo })
        });
        const data = await res.json();
        if (!res.ok || !data.success) {
            mostrarAvisoSistema(data.message || 'No se pudo actualizar el PIN.');
            return;
        }
        document.getElementById('form-cambiar-mi-pin').reset();
        mostrarAvisoSistema(data.message);
    } catch (err) {
        errorControlado(err, 'Error de conexión al actualizar tu PIN.');
    }
}

async function cambiarEstadoCajero(id) {
    const usuario = usuariosCajerosLista.find(u => u.idUsuario === id);
    if (!usuario) return;
    const activo = (usuario.estado || 'Activo').toLowerCase() === 'activo';
    if (!await confirmarSistema(`${activo ? '¿Desactivar' : '¿Reactivar'} a “${usuario.nombreCompleto}”? ${activo ? 'No podrá ingresar ni seguir operando. Sus ventas se conservan.' : 'Podrá volver a ingresar con su cuenta.'}`)) return;
    try {
        const res = await apiFetch(`/usuarios/${id}/estado`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ estado: activo ? 'Inactivo' : 'Activo' }) });
        const data = await res.json();
        if (!res.ok) throw new Error(data.message || 'No se pudo cambiar el estado.');
        await cargarUsuariosCajeros();
    } catch (err) { errorControlado(err, 'No se pudo actualizar el acceso del cajero.'); }
}

async function eliminarUsuarioCajero(id) {
    const usuario = usuariosCajerosLista.find(u => u.idUsuario === id);
    const nombre = usuario ? usuario.nombreCompleto : 'desconocido';
    if (!await confirmarSistema(`¿Eliminar permanentemente al cajero "${nombre}"? Perderá el acceso. Sus ventas, boletas e historial se conservarán.`)) {
        return;
    }

    try {
        const res = await apiFetch(`/usuarios/${id}`, { method: 'DELETE' });
        const data = await res.json();

        if (data.success) {
            mostrarAvisoSistema(`Usuario "${nombre}" eliminado.`);
            cargarUsuariosCajeros();
        } else {
            mostrarAvisoSistema(data.message || 'No se pudo eliminar el usuario.');
        }
    } catch (err) {
        errorControlado(err, 'Error al eliminar el usuario. Verifica la conexión.');
    }
}

// =====================================================
// 9. MÓDULO DE CAJA: APERTURA, ARQUEO E HISTORIAL
// =====================================================
// Punto 11. El backend ya hacia la cuenta (SUM de las ventas del turno); esto
// es solo la pantalla: leer el turno, mostrar cuanto DEBERIA haber en gaveta y
// comparar con lo que el cajero conto.

let paginaHistorialCaja = 0;
let paginaVentasMiCaja = 0;
let cajaPanelPreparado = false;
let cajaOperacionEnCurso = false;
let cajaActualVista = null;
let movimientoCajaTurno = null;
let movimientoCajaClave = null;

function mostrarMensajeCaja(texto) { set('caja-mensaje', texto); set('cierre-aviso', texto); }
function cajaEsAdmin() { return obtenerSesionItem('usuario_rol') === 'ADMIN'; }

async function cargarCierreCaja() {
    if (!cajaPanelPreparado) {
        cajaPanelPreparado = true;
        document.querySelectorAll('[data-caja-admin]').forEach(el => { el.hidden = !cajaEsAdmin(); });
        if (cajaEsAdmin()) {
            document.getElementById('caja-inicio').href = 'inventario.html';
            document.getElementById('caja-volver').hidden = true;
            document.body.classList.add('page-cierre-admin');
            document.querySelector('.page-cierre .brand-title').textContent = 'LAS TULAS';
            document.querySelector('.page-cierre .user-tag i').className = 'fa-solid fa-user';
            set('caja-ventas-titulo', 'VENTAS DE TODOS LOS CAJEROS');
            set('caja-ventas-ayuda', 'Historial de ventas de la caja general. Cada venta conserva su cajero original.');
        }
        document.getElementById('caja-turnos-anteriores').hidden = true;
        document.getElementById('input-efectivo-contado').oninput = actualizarAporteCaja;
        setInterval(() => {
            if (document.hidden || cajaOperacionEnCurso || document.querySelector('dialog[open]')) return;
            cargarTurnoActualCaja();
            if (cajaEsAdmin()) cargarResumenDiaCaja();
        }, 30000);
    }
    await Promise.all([cargarTurnoActualCaja(), cargarVentasMiCaja(), ...(cajaEsAdmin() ? [cargarHistorialCajas(), cargarResumenDiaCaja()] : [])]);
}

async function cargarTurnoActualCaja() {
    const apertura = document.getElementById('panel-apertura');
    const cierre = document.getElementById('panel-cierre');
    const pill = document.getElementById('estado-turno-pill');
    set('caja-usuario', cajaEsAdmin() ? 'Admin' : (obtenerSesionItem('usuario_nombre') || 'Mi caja'));
    try {
        const res = await apiFetch('/caja/actual');
        if (!res.ok) throw new Error('No se pudo consultar el estado de la caja.');
        const caja = await res.json();
        cajaActualVista = caja;
        set('caja-titulo-turno', cajaEsAdmin() ? 'CAJA GENERAL · TODOS LOS CAJEROS' : 'ESTADO DE LA CAJA GENERAL');
        apertura.style.display = !caja.abierto && !caja.requiereRegularizacion && cajaEsAdmin() ? 'block' : 'none';
        cierre.style.display = caja.abierto && cajaEsAdmin() ? 'block' : 'none';
        document.getElementById('caja-sin-turno').hidden = caja.abierto || cajaEsAdmin();
        pill.className = `estado-pill ${caja.abierto ? 'abierta' : 'cerrada'}`;
        pill.textContent = caja.abierto ? 'CAJA ABIERTA' : caja.requiereRegularizacion ? 'TURNOS PENDIENTES' : 'CAJA CERRADA';
        if (cajaEsAdmin()) pintarTurnosAnteriores(caja.turnosPendientes || []);
        if (!caja.abierto || !cajaEsAdmin()) return;
        set('caja-base-inicial', fmtDinero(caja.montoInicial));
        set('caja-total-boletas', caja.totalBoletas);
        set('caja-total-efectivo', fmtDinero(caja.totalEfectivo));
        set('caja-total-digital', fmtDinero(caja.totalDigital));
        set('caja-total-ventas', fmtDinero(caja.totalVentas));
        set('caja-efectivo-esperado', fmtDinero(caja.efectivoEsperado));
        document.getElementById('caja-movimientos-lista').innerHTML = htmlMovimientosCaja(caja.movimientos);
        actualizarAporteCaja();
    } catch (err) {
        cajaActualVista = null;
        pill.textContent = 'NO DISPONIBLE';
        apertura.style.display = 'none'; cierre.style.display = 'none';
        mostrarMensajeCaja(err.message || 'No se pudo consultar la caja.');
    }
}

function pintarTurnosAnteriores(turnos) {
    const panel = document.getElementById('caja-turnos-anteriores');
    panel.hidden = !turnos.length;
    // No reemplazar formularios mientras el administrador escribe su conteo.
    const lista = document.getElementById('caja-anteriores-lista');
    const firma = turnos.map(t => t.idCajaTurno).join(',');
    if (lista.dataset.turnos === firma) return;
    lista.dataset.turnos = firma;
    lista.innerHTML = turnos.map(t => `<form class="caja-aporte" onsubmit="cerrarTurnoAnterior(event, ${Number(t.idCajaTurno)})"><h4>Turno #${Number(t.idCajaTurno)} · ${esc(t.cajeroNombre)}</h4><p class="caja-ayuda">Apertura: ${esc(formatearFecha(t.fechaApertura))}. Ventas: ${fmtDinero(t.totalVentas)}. Efectivo esperado: ${fmtDinero(t.efectivoEsperado)}.</p><div class="form-grid"><div class="form-field"><label for="anterior-contado-${Number(t.idCajaTurno)}">Efectivo contado (S/)</label><input id="anterior-contado-${Number(t.idCajaTurno)}" name="contado" type="number" class="filter-input" min="0" step="0.01" required></div><div class="form-field"><label for="anterior-nota-${Number(t.idCajaTurno)}">Nota del cierre</label><input id="anterior-nota-${Number(t.idCajaTurno)}" name="nota" class="filter-input" maxlength="300" required></div></div><button class="btn-table-action" type="submit">Cerrar turno anterior</button></form>`).join('');
}

async function cerrarTurnoAnterior(event, id) {
    event.preventDefault();
    if (cajaOperacionEnCurso || !cajaEsAdmin()) return;
    const form = event.currentTarget;
    if (!await confirmarSistema('¿Cerrar este turno anterior con el conteo indicado? El arqueo se conservará en el historial.')) return;
    bloquearOperacionCaja(true);
    try {
        const res = await apiFetch(`/caja/turnos-anteriores/${id}/cierre`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ efectivoContado: Number(form.elements.contado.value), observaciones: form.elements.nota.value.trim() }) });
        const data = await res.json();
        if (!res.ok) throw new Error(data.message || 'No se pudo cerrar el turno anterior.');
        mostrarMensajeCaja('Turno anterior cerrado.');
        await cargarCierreCaja();
    } catch (err) { mostrarMensajeCaja(err.message); }
    finally { bloquearOperacionCaja(false); }
}

function actualizarAporteCaja() {
    if (!cajaEsAdmin() || !cajaActualVista?.abierto) return;
    const campo = document.getElementById('input-efectivo-contado');
    const alerta = document.getElementById('arqueo-alerta');
    const contado = Number(campo.value);
    if (!campo.value || !Number.isFinite(contado) || contado < 0) {
        alerta.className = 'arqueo-alerta pendiente';
        set('arqueo-alerta-texto', 'Cuenta el efectivo de la gaveta e ingrésalo para comparar.'); return;
    }
    const diferencia = Math.round((contado - Number(cajaActualVista.efectivoEsperado)) * 100) / 100;
    alerta.className = `arqueo-alerta ${diferencia === 0 ? 'cuadre' : 'descuadre'}`;
    set('arqueo-alerta-texto', diferencia === 0 ? 'La caja cuadra. Puedes cerrar el turno.' : `${diferencia > 0 ? 'Sobran' : 'Faltan'} ${fmtDinero(Math.abs(diferencia))}. Puedes registrar un movimiento o cerrar explicando la diferencia.`);
}

function bloquearOperacionCaja(bloquear) {
    cajaOperacionEnCurso = bloquear;
    document.querySelectorAll('[onclick="abrirTurnoCaja()"], [onclick="cerrarTurnoCaja()"], .caja-acciones button, #movimiento-caja-guardar, #caja-anteriores-lista button').forEach(el => el.disabled = bloquear);
}

async function abrirTurnoCaja() {
    if (!cajaEsAdmin() || cajaOperacionEnCurso || !cajaActualVista || cajaActualVista.abierto || cajaActualVista.requiereRegularizacion) return;
    const monto = Number(document.getElementById('input-monto-inicial').value);
    if (!Number.isFinite(monto) || monto < 0) { mostrarMensajeCaja('Ingresa un monto inicial válido.'); return; }
    bloquearOperacionCaja(true);
    try {
        const res = await apiFetch('/caja/apertura', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ montoInicial: monto }) });
        const data = await res.json();
        if (!res.ok) throw new Error(data.message || 'No se pudo abrir la caja.');
        mostrarMensajeCaja('Caja general abierta para todos los cajeros.');
        await cargarCierreCaja();
    } catch (err) { mostrarMensajeCaja(err.message || 'No se pudo conectar. Inténtalo de nuevo.'); }
    finally { bloquearOperacionCaja(false); }
}

async function cerrarTurnoCaja() {
    if (!cajaEsAdmin() || cajaOperacionEnCurso || !cajaActualVista?.abierto) return;
    const campo = document.getElementById('input-efectivo-contado');
    const contado = Number(campo.value);
    if (campo.value === '' || !Number.isFinite(contado) || contado < 0) { mostrarMensajeCaja('Ingresa el efectivo que contaste en la gaveta.'); campo.focus(); return; }
    if (!await confirmarSistema('¿Cerrar la caja general? Todos los cajeros dejarán de cobrar hasta que el administrador vuelva a abrirla.')) return;
    bloquearOperacionCaja(true);
    try {
        const res = await apiFetch('/caja/cierre', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ efectivoContado: contado, observaciones: document.getElementById('input-observaciones').value.trim(), idCajaTurno: cajaActualVista.idCajaTurno }) });
        const data = await res.json();
        if (!res.ok) throw new Error(data.message || 'No se pudo cerrar la caja.');
        mostrarMensajeCaja(Number(data.diferencia) === 0 ? 'Caja general cerrada sin diferencia. Los movimientos quedaron registrados.' : `Caja general cerrada. Diferencia registrada: ${fmtDinero(data.diferencia)}.`);
        campo.value = '';
        document.getElementById('input-observaciones').value = '';
        paginaHistorialCaja = 0;
        await cargarCierreCaja();
    } catch (err) { mostrarMensajeCaja(err.message || 'No se pudo conectar. Inténtalo de nuevo.'); }
    finally { bloquearOperacionCaja(false); }
}

function abrirMovimientoCaja(tipo) {
    if (!cajaEsAdmin() || cajaOperacionEnCurso || !cajaActualVista?.abierto) return;
    movimientoCajaTurno = cajaActualVista.idCajaTurno;
    if (!globalThis.crypto?.randomUUID) { mostrarMensajeCaja('Este navegador no permite generar una referencia segura para el movimiento.'); return; }
    movimientoCajaClave = crypto.randomUUID();
    document.getElementById('form-movimiento-caja').reset();
    document.getElementById('movimiento-caja-tipo').value = tipo;
    set('movimiento-caja-error', '');
    describirMovimientoCaja();
    document.getElementById('modal-movimiento-caja').showModal();
    document.getElementById('movimiento-caja-monto').focus();
}

function describirMovimientoCaja() {
    const tipo = document.getElementById('movimiento-caja-tipo').value;
    set('movimiento-caja-titulo', tipo === 'RETIRO_CAJA' ? 'REGISTRAR RETIRO' : 'AGREGAR DINERO');
    set('movimiento-caja-ayuda', tipo === 'REPOSICION_CAJA'
        ? 'Cubre dinero que falta. El efectivo esperado no cambia. Después, vuelve a contar la gaveta incluyendo esta reposición.'
        : tipo === 'INGRESO_CAJA' ? 'Añade fondos adicionales. El efectivo esperado aumenta por este monto, sin aumentar las ventas.'
        : 'Registra dinero que sale, por ejemplo para pagar delivery. El efectivo esperado disminuye por este monto.');
}

async function guardarMovimientoCaja(event) {
    event.preventDefault();
    if (!cajaEsAdmin() || cajaOperacionEnCurso) return;
    const monto = document.getElementById('movimiento-caja-monto');
    const motivo = document.getElementById('movimiento-caja-motivo');
    if (!Number.isFinite(Number(monto.value)) || Number(monto.value) <= 0 || !motivo.value.trim()) { set('movimiento-caja-error', 'Indica un monto válido y explica el motivo.'); return; }
    bloquearOperacionCaja(true);
    try {
        const res = await apiFetch('/caja/movimientos', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ tipo: document.getElementById('movimiento-caja-tipo').value, monto: monto.value, motivo: motivo.value.trim(), idCajaTurno: movimientoCajaTurno, claveOperacion: movimientoCajaClave }) });
        const data = await res.json();
        if (!res.ok) throw new Error(data.message || 'No se pudo registrar el movimiento.');
        document.getElementById('modal-movimiento-caja').close();
        mostrarMensajeCaja('Movimiento guardado con su monto y motivo. Vuelve a contar el efectivo antes de cerrar.');
        document.getElementById('input-efectivo-contado').value = '';
        await cargarCierreCaja();
    } catch (err) { set('movimiento-caja-error', err.message || 'No se pudo registrar el movimiento.'); }
    finally { bloquearOperacionCaja(false); }
}

function nombreMovimientoCaja(tipo) { return ({ INGRESO_CAJA: 'Ingreso', RETIRO_CAJA: 'Retiro', REPOSICION_CAJA: 'Reposición' })[tipo] || 'Movimiento'; }
function htmlMovimientosCaja(movimientos) {
    return (movimientos || []).map(m => `<li><div><strong>${esc(nombreMovimientoCaja(m.tipo))}</strong><span>${fmtDinero(m.monto)}</span></div><p>${esc(m.motivo)}</p><small>${esc(m.responsable)} · ${esc(formatearFecha(m.fecha))} ${esc(formatearHora(m.fecha))}</small></li>`).join('') || '<li class="caja-ayuda">Sin movimientos de efectivo en este turno.</li>';
}

async function cargarResumenDiaCaja() {
    try {
        const res = await apiFetch('/caja/resumen-dia');
        if (!res.ok) throw new Error('No se pudo actualizar el resumen del día. Pulsa Actualizar para reintentar.');
        const data = await res.json();
        set('caja-dia-fecha', `${formatearFecha(data.fecha + 'T12:00:00')} · Todas las ventas emitidas, de todos los cajeros.`);
        set('caja-dia-total', fmtDinero(data.resumen.totalEmitido));
        set('caja-dia-boletas', data.resumen.boletasEmitidas);
        set('caja-dia-efectivo', fmtDinero(data.resumen.efectivo));
        set('caja-dia-digital', fmtDinero(data.resumen.digital));
        const barra = (nombre, valor, porcentaje) => `<li><div class="catalogo-bar-label"><strong>${esc(nombre)}</strong><span>${valor}</span></div><div class="catalogo-bar-track" aria-hidden="true"><span style="width:${Math.min(100, Math.max(0, Number(porcentaje) || 0))}%"></span></div><p>${Number(porcentaje || 0).toFixed(1)} % del total del día</p></li>`;
        document.getElementById('caja-dia-categorias').innerHTML = (data.catalogo.categorias || []).map(c => barra(c.nombre, fmtDinero(c.monto), c.porcentaje)).join('') || '<li class="caja-ayuda">Todavía no hay ventas emitidas hoy.</li>';
        document.getElementById('caja-dia-productos').innerHTML = (data.catalogo.productos || []).slice(0, 5).map(p => barra(p.nombre, `${Number(p.unidades)} uds.`, p.porcentajeUnidades)).join('') || '<li class="caja-ayuda">Los productos aparecerán al registrar ventas.</li>';
        set('caja-dia-aviso', `Actualizado a las ${new Date().toLocaleTimeString('es-PE', { hour: '2-digit', minute: '2-digit' })}. Los movimientos de caja no se suman a las ventas.`);
    } catch (err) { set('caja-dia-aviso', err.message); }
}

async function cargarHistorialCajas() {
    const tbody = document.getElementById('tabla-historial-caja-body');
    if (!tbody || !cajaEsAdmin()) return;
    try {
        const res = await apiFetch(`/caja/turnos?pagina=${paginaHistorialCaja}&tamano=10`);
        if (!res.ok) throw new Error('No se pudo cargar el historial.');
        const data = await res.json();
        pintarControlesPaginacion('caja', data, n => { paginaHistorialCaja = n; cargarHistorialCajas(); }, 'turnos');
        tbody.innerHTML = (data.contenido || []).map(t => `<tr>
            <td>${esc(formatearFecha(t.fechaApertura))} ${esc(formatearHora(t.fechaApertura))}</td>
            <td>${t.fechaCierre ? esc(formatearFecha(t.fechaCierre)) + ' ' + esc(formatearHora(t.fechaCierre)) : '—'}</td>
            <td>${esc(t.cajeroNombre || 'Sin asignar')}</td><td>${esc(t.totalBoletas)}</td><td><strong>${fmtDinero(t.totalVentas)}</strong></td>
            <td>${fmtDinero(t.efectivoEsperado)}</td><td>${t.efectivoContado == null ? '—' : fmtDinero(t.efectivoContado)}</td>
            <td>${t.efectivoContado == null ? '—' : fmtDinero(t.diferencia)}</td>
            <td><span class="estado-pill ${t.estado === 'CERRADA' ? 'cerrada' : 'abierta'}">${esc(t.estado)}</span></td>
            <td>${t.observaciones || t.movimientos?.length ? `<details class="caja-observaciones"><summary>Ver detalle</summary>${t.observaciones ? `<p>${esc(t.observaciones)}</p>` : ''}<ul class="caja-movimientos-lista">${htmlMovimientosCaja(t.movimientos)}</ul></details>` : '—'}</td></tr>`).join('') || '<tr><td colspan="10">Todavía no hay turnos de caja.</td></tr>';
    } catch (err) { tbody.innerHTML = '<tr><td colspan="10">No se pudo cargar el historial.</td></tr>'; }
}

async function cargarVentasMiCaja() {
    const tbody = document.getElementById('caja-ventas-body');
    if (!tbody) return;
    try {
        const res = await apiFetch(`/caja/ventas?pagina=${paginaVentasMiCaja}&tamano=10`);
        if (!res.ok) throw new Error('No se pudieron consultar las ventas.');
        const data = await res.json();
        pintarControlesPaginacion('mi-caja-ventas', data, n => { paginaVentasMiCaja = n; cargarVentasMiCaja(); });
        tbody.innerHTML = (data.contenido || []).map(v => `<tr><td><strong>${esc(v.numeroComprobante)}</strong></td><td>${esc(formatearFecha(v.fechaVenta))} ${esc(formatearHora(v.fechaVenta))}</td><td>${esc(v.cajeroNombre)}</td><td>${esc(v.metodoPagoNombre || v.metodoPago)}</td><td>${fmtDinero(v.total)}</td><td>${badgeEstadoComprobante(v.estado)}</td></tr>`).join('') || '<tr><td colspan="6">Todavía no hay ventas registradas.</td></tr>';
    } catch (err) { tbody.innerHTML = '<tr><td colspan="6">No se pudieron consultar las ventas. Pulsa Actualizar para reintentar.</td></tr>'; }
}

function set(id, valor) {
    const el = document.getElementById(id);
    if (el) el.innerText = valor;
}

// =====================================================
// 10. MÓDULO DE AUDITORIA
// =====================================================
// Punto 12. La bitacora se escribe en el backend con AuditoriaService; aca solo
// se lee y se filtra. Es de solo lectura a proposito: una bitacora que se pueda
// editar no es una bitacora.

async function cargarAuditoria() {
    const params = new URLSearchParams({
        pagina: String(paginaAuditoria),
        tamano: String(TAMANO_PAGINA_VENTAS)
    });
    const tabla = document.getElementById('filtro-auditoria-tabla')?.value;
    const accion = document.getElementById('filtro-auditoria-accion')?.value;
    const desde = document.getElementById('filtro-auditoria-f-inicio')?.value;
    const hasta = document.getElementById('filtro-auditoria-f-fin')?.value;
    if (tabla) params.append('tabla', tabla);
    if (accion) params.append('accion', accion);
    if (desde) params.append('desde', desde);
    if (hasta) params.append('hasta', hasta);

    try {
        const res = await apiFetch(`/auditoria?${params.toString()}`);
        if (!res.ok) throw new Error('Error cargando auditoria');

        const data = await res.json();
        renderizarTablaAuditoria(data.contenido || []);
        pintarControlesPaginacion('auditoria', data, (nuevaPagina) => {
            paginaAuditoria = nuevaPagina;
            cargarAuditoria();
        }, 'registros');
    } catch (err) {
        renderizarTablaAuditoria([]);
    }
}

let paginaAuditoria = 0;

function filtrarAuditoria() {
    paginaAuditoria = 0;
    cargarAuditoria();
}

function renderizarTablaAuditoria(lista) {
    const tbody = document.getElementById('tabla-auditoria-body');
    if (!tbody) return;

    tbody.innerHTML = '';
    if (!lista || lista.length === 0) {
        tbody.innerHTML = '<tr><td colspan="7" style="text-align:center; padding: 22px; color: var(--text-muted);">No hay registros de auditoria.</td></tr>';
        return;
    }

    lista.forEach(a => {
        const tr = document.createElement('tr');
        tr.innerHTML = `
            <td>${esc(formatearFecha(a.fecha))} ${esc(formatearHora(a.fecha))}</td>
            <td>${esc(a.usuario || 'Sistema')}</td>
            <td>${esc(a.tabla || '-')}</td>
            <td>${esc(a.idRegistro ?? '-')}</td>
            <td><span class="estado-pill cerrada">${esc(a.accion || '-')}</span></td>
            <td style="font-size: 0.78rem; color: var(--text-muted);">${esc(a.valorAnterior || '-')}</td>
            <td style="font-size: 0.78rem; color: var(--text-muted);">${esc(a.valorNuevo || '-')}</td>
        `;
        tbody.appendChild(tr);
    });
}

// =====================================================
// 11. ENRUTADOR INICIALIZADOR
// =====================================================
document.addEventListener('DOMContentLoaded', () => {
    iniciarTransicionesPagina();
    const detalle = document.getElementById('sale-detail-dialog');
    detalle?.addEventListener('cancel', event => {
        if (operacionBoletaEnCurso) event.preventDefault();
    });
    detalle?.addEventListener('click', event => {
        if (event.target === detalle) cerrarDetalleBoleta();
    });
    detalle?.addEventListener('close', () => {
        if (detalle.open) return;
        solicitudDetalleBoleta?.abort();
        solicitudDetalleBoleta = null;
        restaurarFocoDetalleBoleta();
    });

    // Aviso que dejo apiFetch al expulsar a alguien por token vencido.
    const aviso = document.getElementById('aviso-sesion');
    if (aviso) {
        const mensaje = sessionStorage.getItem('avisos_logout');
        if (mensaje) {
            aviso.textContent = mensaje;
            aviso.style.display = 'block';
            sessionStorage.removeItem('avisos_logout');
        }
    }

    // Guard de acceso rápido. El backend igual es quien manda: si el token no
    // vale, apiFetch responde 401 y vuelve al login. Esto solo evita parpadeos.
    const rolActual = sessionStorage.getItem('usuario_rol');
    document.body.dataset.role = rolActual || '';
    const esAdmin = rolActual === 'ADMIN';
    const esCajero = rolActual === 'CAJERO';
    configurarBarraLateral(rolActual);

    const nombreCajeroEl = document.getElementById('nombre-cajero');
    if (nombreCajeroEl) {
        nombreCajeroEl.innerText = (esCajero || esAdmin)
            ? (sessionStorage.getItem('usuario_nombre') || 'Cajero')
            : 'Cajero';
    }

    if (document.getElementById('tabla-productos-body')) {
        if (!permitirSesion(['ADMIN'])) return;
        cargarCategoriasInventario().then(obtenerProductos);
    }
    if (document.getElementById('pos-grid-productos')) {
        if (!permitirSesion(['CAJERO', 'ADMIN'])) return;
        prepararAccesosPOS();
        prepararNotasPOS();
        const anchoCarrito = window.matchMedia('(max-width: 720px)');
        const adaptarCarrito = () => {
            alternarCarritoMovil(false);
        };
        const contenidoCarrito = document.getElementById('pos-mobile-cart-content');
        if (contenidoCarrito) contenidoCarrito.inert = anchoCarrito.matches;
        anchoCarrito.addEventListener('change', adaptarCarrito);
        const buscador = document.getElementById('input-buscar-pos');
        if (buscador) buscador.addEventListener('input', () => {
            renderizarPOS();
        });
        document.addEventListener('keydown', (event) => {
            if (document.body.classList.contains('toppings-open') || document.body.classList.contains('notas-open')) return;
            const panel = document.querySelector('.pos-order-panel');
            if (!panel?.classList.contains('is-open')) return;
            if (event.key === 'Escape') {
                alternarCarritoMovil(false);
                return;
            }
            if (event.key !== 'Tab') return;

            const focoPosible = Array.from(panel.querySelectorAll(
                'button:not([disabled]), a[href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'
            )).filter(elemento => elemento.getClientRects().length > 0);
            if (focoPosible.length === 0) return;
            const primero = focoPosible[0];
            const ultimo = focoPosible[focoPosible.length - 1];
            if (!panel.contains(document.activeElement)) {
                event.preventDefault();
                (event.shiftKey ? ultimo : primero).focus();
            } else if (event.shiftKey && document.activeElement === primero) {
                event.preventDefault();
                ultimo.focus();
            } else if (!event.shiftKey && document.activeElement === ultimo) {
                event.preventDefault();
                primero.focus();
            }
        });
        cargarPOS();
    }
    if (document.getElementById('pago-resumen-total')) {
        if (!permitirSesion(['CAJERO', 'ADMIN'])) return;
        cargarPantallaPago();
    }
    if (document.getElementById('tabla-reporte-boletas-body')) {
        if (!permitirSesion(['ADMIN'])) return;
        cargarReporteBoletas();
    }
    if (document.getElementById('tabla-anulacion-body')) {
        if (!permitirSesion(['ADMIN'])) return;
        cargarAnulacionBoletas();
    }
    if (document.getElementById('tabla-reporte-general-body')) {
        if (!permitirSesion(['ADMIN'])) return;
        cargarReporteGeneral();
    }
    if (document.getElementById('tabla-usuarios-cajeros-body')) {
        if (!permitirSesion(['ADMIN'])) return;
        cargarUsuariosCajeros();
    }
    if (document.getElementById('tabla-historial-caja-body')) {
        if (!permitirSesion(['CAJERO', 'ADMIN'])) return;
        cargarCierreCaja();
    }
    if (document.getElementById('tabla-auditoria-body')) {
        if (!permitirSesion(['ADMIN'])) return;
        cargarAuditoria();
    }
});

// Cupos por producto: la categoría solo filtra; guardar modifica una sola fila.
async function abrirConfiguracionIncluidos() {
    await obtenerProductos();
    const selector = document.getElementById('incluidos-categoria');
    selector.replaceChildren(new Option('Selecciona una categoría', ''));
    categoriasCatalogo.filter(c => !c.esTopping && !c.esSabor && !c.esFruta)
        .forEach(c => selector.add(new Option(c.nombre, c.idCategoria)));
    document.getElementById('incluidos-estado').textContent = '';
    cargarProductosIncluidos();
    document.getElementById('config-incluidos').showModal();
}
function cargarProductosIncluidos() {
    const categoria = document.getElementById('incluidos-categoria').value;
    const selector = document.getElementById('incluidos-producto');
    selector.replaceChildren(new Option('Selecciona un producto', ''));
    productosLista.filter(p => String(p.idCategoria ?? p.id_categoria) === categoria && p.activo !== false && !esAdicional(p))
        .forEach(p => selector.add(new Option(`${p.referencia} · ${p.nombreProducto || p.nombre_producto}`, p.idProducto ?? p.id_producto)));
    mostrarCuposIncluidos();
}
function mostrarCuposIncluidos() {
    const id = document.getElementById('incluidos-producto').value;
    const producto = productosLista.find(p => idProductoPOS(p) === id);
    for (const [tipo, campo] of [['toppings', 'toppingsIncluidos'], ['frutas', 'frutasIncluidas']]) {
        const input = document.getElementById(`incluidos-${tipo}`);
        input.value = producto?.[campo] || 0;
        input.disabled = !producto;
    }
    document.querySelector('#form-incluidos button[type="submit"]').disabled = !producto;
    window.sincronizarConfiguracionIncluidos?.();
}
async function guardarConfiguracionIncluidos(event) {
    event.preventDefault();
    const id = document.getElementById('incluidos-producto').value;
    if (!id) return;
    const boton = document.querySelector('#form-incluidos button[type="submit"]');
    if (boton.disabled) return;
    const estado = document.getElementById('incluidos-estado');
    const payload = {
        toppingsIncluidos: Number(document.getElementById('incluidos-toppings').value),
        frutasIncluidas: Number(document.getElementById('incluidos-frutas').value)
    };
    if (!Object.values(payload).every(n => Number.isInteger(n) && n >= 0 && n <= 25)) return;
    boton.disabled = true;
    window.estadoConfiguracionIncluidos?.('Guardando configuración…', 'loading');
    window.bloquearConfiguracionIncluidos?.(true);
    try {
        const res = await apiFetch(`/productos/${id}/incluidos`, {
            method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload)
        });
        const data = await res.json();
        if (!res.ok) throw new Error(data.message || 'No se pudo guardar la configuración.');
        const producto = productosLista.find(p => idProductoPOS(p) === id);
        if (producto) Object.assign(producto, data.producto);
        estado.textContent = `Configuración guardada para ${producto?.nombreProducto || 'este producto'}. Incluye ${payload.toppingsIncluidos} ${payload.toppingsIncluidos === 1 ? 'topping' : 'toppings'} y ${payload.frutasIncluidas} ${payload.frutasIncluidas === 1 ? 'fruta' : 'frutas'} por unidad.`;
        estado.dataset.kind = 'success';
    } catch (error) { estado.textContent = error.message; estado.dataset.kind = 'error'; }
    finally {
        boton.disabled = false;
        window.bloquearConfiguracionIncluidos?.(false);
    }
}
