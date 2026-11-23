// Interacciones compartidas entre las pantallas de Las Tulas.
(function () {
    'use strict';

    function prepararNavegacion() {
        const pagina = location.pathname.split('/').pop() || 'index.html';
        document.querySelectorAll('.nav-link-item').forEach(link => {
            link.querySelectorAll('i').forEach(icono => icono.setAttribute('aria-hidden', 'true'));
            const activa = (link.getAttribute('href') || '').split('/').pop() === pagina;
            link.classList.toggle('active', activa);
            if (activa) link.setAttribute('aria-current', 'page');
            else link.removeAttribute('aria-current');
        });
        const barra = document.querySelector('.sidebar-nav');
        if (!barra) return;
        barra.id = 'navegacion-negocio';
        barra.setAttribute('aria-label', 'Navegación del negocio');
        const lista = barra.querySelector('.sidebar-menu');
        const activa = lista?.querySelector('.nav-link-item.active');
        if (!lista) return;
        if (activa && matchMedia('(max-width: 720px)').matches) {
            requestAnimationFrame(() => {
                const izquierda = activa.offsetLeft - lista.offsetLeft - (lista.clientWidth - activa.offsetWidth) / 2;
                lista.scrollLeft = Math.max(0, izquierda);
            });
        }
        lista.addEventListener('keydown', event => {
            if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
            const enlaces = [...lista.querySelectorAll('.nav-link-item')];
            const indice = enlaces.indexOf(document.activeElement);
            if (indice < 0) return;
            const siguiente = enlaces[indice + (event.key === 'ArrowRight' ? 1 : -1)];
            if (!siguiente) return;
            event.preventDefault();
            siguiente.focus();
            siguiente.scrollIntoView({ block: 'nearest', inline: 'center', behavior: matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth' });
        });
    }

    function prepararTecladoPin() {
        const modal = document.getElementById('pinpad-modal');
        if (!modal) return;
        modal.addEventListener('click', event => {
            if (event.target === modal) cerrarPinPad();
        });
        document.addEventListener('keydown', event => {
            if (modal.getAttribute('aria-hidden') !== 'false') return;
            if (event.key === 'Escape') { event.preventDefault(); cerrarPinPad(); return; }
            if (event.ctrlKey || event.metaKey || event.altKey) return;
            if (event.target.id !== 'pin-input' && /^[0-9]$/.test(event.key)) {
                event.preventDefault(); agregarNumero(event.key); return;
            }
            if (event.target.id !== 'pin-input' && event.key === 'Backspace') {
                event.preventDefault(); borrarNumero(); return;
            }
            if (event.key === 'Enter' && !event.target.closest('.btn-cancel')) {
                event.preventDefault(); validarPin(); return;
            }
            if (event.key !== 'Tab') return;
            const controles = [...modal.querySelectorAll('button:not(:disabled), input:not(:disabled)')];
            const primero = controles[0];
            const ultimo = controles[controles.length - 1];
            if (event.shiftKey && document.activeElement === primero) {
                event.preventDefault(); ultimo.focus();
            } else if (!event.shiftKey && document.activeElement === ultimo) {
                event.preventDefault(); primero.focus();
            }
        });
    }

    function prepararEncuadreModales() {
        let framePendiente = 0;
        function ajustar() {
            if (framePendiente) return;
            framePendiente = requestAnimationFrame(() => {
                const viewport = window.visualViewport;
                document.documentElement.style.setProperty('--modal-viewport-height', `${viewport?.height || window.innerHeight}px`);
                document.documentElement.style.setProperty('--modal-viewport-top', `${viewport?.offsetTop || 0}px`);
                framePendiente = 0;
            });
        }
        ajustar();
        window.addEventListener('resize', ajustar, { passive: true });
        window.visualViewport?.addEventListener('resize', ajustar, { passive: true });
        window.visualViewport?.addEventListener('scroll', ajustar, { passive: true });
    }

    // Los selects originales conservan la validación, los valores y sus onchange.
    function prepararSelectores() {
        const controles = [];
        let abierto = null;
        const normalizar = texto => texto.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLocaleLowerCase('es-PE');

        function cerrar() {
            if (!abierto) return;
            const { panel, trigger } = abierto;
            if (panel.popover && panel.matches(':popover-open')) panel.hidePopover();
            panel.hidden = true;
            trigger.setAttribute('aria-expanded', 'false');
            trigger.removeAttribute('aria-activedescendant');
            abierto = null;
        }

        function activar(control, indice) {
            control.activo = indice;
            control.panel.querySelectorAll('[role="option"]').forEach(opcion => {
                opcion.classList.toggle('is-active', Number(opcion.dataset.index) === indice);
            });
            const opcion = control.panel.querySelector(`[data-index="${indice}"]`);
            if (opcion) {
                control.trigger.setAttribute('aria-activedescendant', opcion.id);
                // Desplaza únicamente la lista, sin mover el formulario o la página.
                const arriba = opcion.offsetTop - control.panel.clientTop;
                const abajo = arriba + opcion.offsetHeight;
                if (arriba < control.panel.scrollTop) control.panel.scrollTop = arriba;
                else if (abajo > control.panel.scrollTop + control.panel.clientHeight) control.panel.scrollTop = abajo - control.panel.clientHeight;
            } else control.trigger.removeAttribute('aria-activedescendant');
        }

        function opcionesDisponibles(control) {
            return [...control.select.options].map((opcion, indice) => ({ opcion, indice }))
                .filter(({ opcion }) => !opcion.hidden && !opcion.disabled && !opcion.parentElement.disabled);
        }

        function sincronizar(control, actualizarOpciones = false) {
            const { select, trigger, valor } = control;
            valor.textContent = select.selectedOptions[0]?.label || 'Selecciona una opción';
            trigger.disabled = select.disabled;
            trigger.setAttribute('aria-required', String(select.required));
            if (control.invalido) trigger.setAttribute('aria-invalid', String(!select.validity.valid));
            if (select.disabled && abierto === control) cerrar();
            if (actualizarOpciones && abierto === control) {
                renderizar(control);
                activar(control, select.selectedIndex);
            }
        }

        function elegir(control, indice) {
            if (control.select.disabled || !opcionesDisponibles(control).some(opcion => opcion.indice === indice)) return;
            const cambio = control.select.selectedIndex !== indice;
            control.select.selectedIndex = indice;
            sincronizar(control);
            cerrar();
            if (cambio) {
                control.select.dispatchEvent(new Event('input', { bubbles: true }));
                control.select.dispatchEvent(new Event('change', { bubbles: true }));
            }
        }

        function renderizar(control) {
            control.panel.replaceChildren();
            [...control.select.options].forEach((opcion, indice) => {
                if (opcion.hidden) return;
                const fila = document.createElement('div');
                fila.className = 'config-picker-option ui-select-option';
                fila.id = `${control.select.id}-option-${indice}`;
                fila.dataset.index = indice;
                fila.setAttribute('role', 'option');
                fila.setAttribute('aria-selected', String(control.select.selectedIndex === indice));
                fila.setAttribute('aria-disabled', String(opcion.disabled || Boolean(opcion.parentElement.disabled)));
                const texto = document.createElement('span');
                texto.textContent = opcion.label;
                fila.append(texto, crearIcono('m5 12 4 4L19 6'));
                fila.addEventListener('click', () => elegir(control, indice));
                control.panel.append(fila);
            });
            if (!control.panel.children.length) {
                const mensaje = document.createElement('p');
                mensaje.className = 'config-picker-empty';
                mensaje.textContent = 'No hay opciones disponibles.';
                control.panel.append(mensaje);
            }
        }

        function crearIcono(trazo) {
            const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
            svg.setAttribute('viewBox', '0 0 24 24');
            svg.setAttribute('fill', 'none');
            svg.setAttribute('stroke', 'currentColor');
            svg.setAttribute('stroke-width', '2');
            svg.setAttribute('stroke-linecap', 'round');
            svg.setAttribute('stroke-linejoin', 'round');
            svg.setAttribute('aria-hidden', 'true');
            const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
            path.setAttribute('d', trazo);
            svg.append(path);
            return svg;
        }

        function abrir(control) {
            cerrar();
            sincronizar(control);
            if (control.trigger.disabled) return;
            renderizar(control);
            const { panel, trigger } = control;
            panel.hidden = false;
            if (panel.popover) panel.showPopover();
            const rect = trigger.getBoundingClientRect();
            const viewport = window.visualViewport;
            const bordeIzquierdo = (viewport?.offsetLeft || 0) + 12;
            const bordeSuperior = (viewport?.offsetTop || 0) + 12;
            const bordeDerecho = bordeIzquierdo + (viewport?.width || innerWidth) - 24;
            const bordeInferior = bordeSuperior + (viewport?.height || innerHeight) - 24;
            const debajo = Math.max(0, bordeInferior - rect.bottom - 8);
            const encima = Math.max(0, rect.top - bordeSuperior - 8);
            const haciaAbajo = debajo >= Math.min(200, panel.scrollHeight) || debajo >= encima;
            panel.style.width = `${Math.min(rect.width, bordeDerecho - bordeIzquierdo)}px`;
            panel.style.maxHeight = `${Math.min(288, haciaAbajo ? debajo : encima)}px`;
            panel.style.left = `${Math.max(bordeIzquierdo, Math.min(rect.left, bordeDerecho - panel.offsetWidth))}px`;
            panel.style.top = `${haciaAbajo ? rect.bottom + 8 : rect.top - panel.offsetHeight - 8}px`;
            trigger.setAttribute('aria-expanded', 'true');
            abierto = control;
            control.busqueda = '';
            const disponibles = opcionesDisponibles(control);
            activar(control, disponibles.some(opcion => opcion.indice === control.select.selectedIndex)
                ? control.select.selectedIndex : (disponibles[0]?.indice ?? -1));
        }

        document.querySelectorAll('select:not([hidden]):not([multiple])').forEach((select, numero) => {
            if (select.size > 1) return;
            select.id ||= `selector-${numero}`;
            const etiquetas = [...select.labels];
            const wrapper = document.createElement('div');
            wrapper.className = `ui-select${select.classList.contains('select-clean') ? ' ui-select--compact' : ''}`;
            const trigger = document.createElement('button');
            trigger.type = 'button';
            trigger.id = `${select.id}-trigger`;
            trigger.className = 'config-picker-trigger ui-select-trigger';
            trigger.setAttribute('role', 'combobox');
            trigger.setAttribute('aria-haspopup', 'listbox');
            trigger.setAttribute('aria-expanded', 'false');
            trigger.setAttribute('aria-controls', `${select.id}-listbox`);
            const valor = document.createElement('span');
            valor.id = `${select.id}-value`;
            etiquetas.forEach((etiqueta, indice) => {
                etiqueta.id ||= `${select.id}-label-${indice}`;
                etiqueta.htmlFor = trigger.id;
            });
            const nombre = etiquetas.map(etiqueta => etiqueta.id).join(' ') || select.getAttribute('aria-labelledby');
            if (nombre) trigger.setAttribute('aria-labelledby', `${nombre} ${valor.id}`);
            else trigger.setAttribute('aria-label', select.getAttribute('aria-label') || 'Selecciona una opción');
            if (select.hasAttribute('aria-describedby')) trigger.setAttribute('aria-describedby', select.getAttribute('aria-describedby'));
            trigger.append(valor, crearIcono('m6 9 6 6 6-6'));
            const panel = document.createElement('div');
            panel.id = `${select.id}-listbox`;
            panel.className = 'config-picker-panel ui-select-panel';
            panel.hidden = true;
            panel.setAttribute('role', 'listbox');
            if (nombre) panel.setAttribute('aria-labelledby', nombre);
            else panel.setAttribute('aria-label', select.getAttribute('aria-label') || 'Opciones');
            if (typeof panel.showPopover === 'function') panel.popover = 'manual';
            select.before(wrapper);
            wrapper.append(select, trigger);
            // El popover evita recortes por tarjetas y diálogos con scroll.
            (panel.popover ? wrapper : (select.closest('dialog') || document.body)).append(panel);
            select.classList.add('ui-select-native');
            select.tabIndex = -1;
            select.setAttribute('aria-hidden', 'true');
            const control = { select, trigger, valor, panel, activo: -1, busqueda: '', ultimaTecla: 0, invalido: false };
            controles.push(control);
            sincronizar(control);
            new MutationObserver(() => sincronizar(control, true)).observe(select, {
                childList: true, subtree: true, characterData: true, attributes: true,
                attributeFilter: ['disabled', 'required', 'selected', 'label', 'hidden', 'value']
            });
            select.addEventListener('invalid', event => {
                event.preventDefault();
                control.invalido = true;
                sincronizar(control);
                trigger.focus();
            });
            trigger.addEventListener('click', () => abierto === control ? cerrar() : abrir(control));
            trigger.addEventListener('keydown', event => {
                if (event.ctrlKey || event.metaKey || (event.altKey && event.key !== 'ArrowDown')) return;
                if (event.key === 'Escape' && abierto === control) {
                    event.preventDefault(); event.stopPropagation(); cerrar(); return;
                }
                if (event.key === 'Tab') {
                    if (abierto === control) elegir(control, control.activo);
                    return;
                }
                if (['Enter', ' '].includes(event.key)) {
                    event.preventDefault();
                    if (abierto === control) elegir(control, control.activo);
                    else abrir(control);
                    return;
                }
                const navegacion = ['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key);
                const escribir = event.key.length === 1 && !event.altKey;
                if (!navegacion && !escribir) return;
                event.preventDefault();
                const estabaAbierto = abierto === control;
                if (!estabaAbierto) abrir(control);
                const opciones = opcionesDisponibles(control);
                const indice = opciones.findIndex(opcion => opcion.indice === control.activo);
                let siguiente = indice;
                if (event.key === 'Home') siguiente = 0;
                else if (event.key === 'End') siguiente = opciones.length - 1;
                else if (estabaAbierto && event.key === 'ArrowDown') siguiente = Math.min(indice + 1, opciones.length - 1);
                else if (estabaAbierto && event.key === 'ArrowUp') siguiente = Math.max(indice - 1, 0);
                else if (escribir) {
                    const ahora = Date.now();
                    control.busqueda = ahora - control.ultimaTecla > 700 ? event.key : control.busqueda + event.key;
                    control.ultimaTecla = ahora;
                    const busqueda = normalizar(control.busqueda);
                    const repetida = [...busqueda].every(letra => letra === busqueda[0]);
                    const ordenadas = [...opciones.slice(indice + 1), ...opciones.slice(0, indice + 1)];
                    const coincidencia = ordenadas.find(({ opcion }) => normalizar(opcion.label).startsWith(repetida ? busqueda[0] : busqueda));
                    if (coincidencia) siguiente = opciones.indexOf(coincidencia);
                }
                activar(control, opciones[siguiente]?.indice ?? -1);
            });
            select.closest('dialog')?.addEventListener('close', () => { if (abierto === control) cerrar(); });
        });

        const sincronizarTodos = () => controles.forEach(control => sincronizar(control));
        document.addEventListener('change', sincronizarTodos);
        document.addEventListener('click', sincronizarTodos);
        document.addEventListener('reset', () => queueMicrotask(() => {
            cerrar();
            controles.forEach(control => { control.invalido = false; control.trigger.removeAttribute('aria-invalid'); });
            sincronizarTodos();
        }));
        document.addEventListener('pointerdown', event => {
            if (abierto && !abierto.trigger.contains(event.target) && !abierto.panel.contains(event.target)) cerrar();
        });
        document.addEventListener('focusin', event => {
            if (abierto && event.target !== abierto.trigger && !abierto.panel.contains(event.target)) cerrar();
            sincronizarTodos();
        });
        document.addEventListener('scroll', event => {
            if (abierto && !abierto.panel.contains(event.target)) cerrar();
        }, true);
        window.addEventListener('resize', cerrar, { passive: true });
        window.visualViewport?.addEventListener('resize', cerrar, { passive: true });
        window.addEventListener('pageshow', sincronizarTodos);
    }

    document.addEventListener('DOMContentLoaded', () => {
        prepararNavegacion();
        prepararTecladoPin();
        prepararEncuadreModales();
        prepararSelectores();
        document.querySelectorAll('.table-scroll-region').forEach(region => {
            if (region.querySelector('.inventory-table')) return;
            const ayuda = document.createElement('p');
            ayuda.className = 'table-scroll-hint';
            ayuda.textContent = 'Desliza la tabla para ver más columnas.';
            region.before(ayuda);
        });
    });
    // Al volver con el gesto del navegador la página debe estar visible.
    window.addEventListener('pageshow', () => document.body.classList.remove('is-leaving'));
})();
