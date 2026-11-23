// Presentación del formulario. Los selects internos conservan los valores
// y los mismos eventos que usa la configuración existente.
(() => {
    const dialog = document.getElementById('config-incluidos');
    if (!dialog) return;
    let saving = false;
    const pickers = [...dialog.querySelectorAll('.config-picker')];
    const selectOf = picker => document.getElementById(picker.dataset.picker);
    const visibleOptions = picker => [...picker.querySelectorAll('.config-picker-option')];

    function closePicker(picker, restoreFocus = false) {
        picker.querySelector('.config-picker-panel').hidden = true;
        picker.querySelector('.config-picker-trigger').setAttribute('aria-expanded', 'false');
        if (restoreFocus) picker.querySelector('.config-picker-trigger').focus();
    }
    function renderOptions(picker) {
        const select = selectOf(picker);
        const query = picker.querySelector('.config-picker-search').value.trim();
        const normalize = value => value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLocaleLowerCase('es-PE');
        const list = picker.querySelector('.config-picker-list');
        list.replaceChildren();
        [...select.options].filter(option => option.value && normalize(option.textContent).includes(normalize(query)))
            .forEach(option => {
                const button = document.createElement('button');
                button.type = 'button';
                button.className = 'config-picker-option';
                button.setAttribute('role', 'option');
                button.setAttribute('aria-selected', String(select.value === option.value));
                button.tabIndex = -1;
                const label = document.createElement('span');
                label.textContent = option.textContent;
                const check = document.createElement('i');
                check.className = select.value === option.value ? 'fa-solid fa-check' : '';
                check.setAttribute('aria-hidden', 'true');
                button.append(label, check);
                button.addEventListener('click', () => {
                    if (saving) return;
                    select.value = option.value;
                    closePicker(picker, true);
                    select.dispatchEvent(new Event('change', { bubbles: true }));
                });
                list.append(button);
            });
        if (!list.children.length) {
            const message = document.createElement('p');
            message.className = 'config-picker-empty';
            message.textContent = 'No hay coincidencias. Prueba con otro nombre.';
            list.append(message);
        }
    }
    function openPicker(picker, focusOption = false) {
        pickers.forEach(other => closePicker(other));
        picker.querySelector('.config-picker-search').value = '';
        renderOptions(picker);
        picker.querySelector('.config-picker-panel').hidden = false;
        picker.querySelector('.config-picker-trigger').setAttribute('aria-expanded', 'true');
        if (focusOption) (picker.querySelector('[aria-selected="true"]') || visibleOptions(picker)[0])?.focus();
    }
    function syncSteppers() {
        dialog.querySelectorAll('[data-step]').forEach(button => {
            const input = document.getElementById(button.dataset.input);
            const step = Number(button.dataset.step);
            button.disabled = saving || input.disabled || (step < 0 ? Number(input.value) <= 0 : Number(input.value) >= 25);
        });
    }
    window.estadoConfiguracionIncluidos = (message, kind = 'info') => {
        const status = document.getElementById('incluidos-estado');
        status.dataset.kind = kind;
        status.textContent = message;
    };
    window.sincronizarConfiguracionIncluidos = () => {
        pickers.forEach(picker => {
            const select = selectOf(picker);
            const trigger = picker.querySelector('.config-picker-trigger');
            trigger.querySelector('span').textContent = select.selectedOptions[0]?.textContent || 'Selecciona una opción';
            trigger.disabled = saving || [...select.options].every(option => !option.value);
            closePicker(picker);
        });
        syncSteppers();
        const category = document.getElementById('incluidos-categoria');
        const product = document.getElementById('incluidos-producto');
        if (!category.value) {
            window.estadoConfiguracionIncluidos('Selecciona una categoría y después el producto que quieras configurar.');
        } else if (!product.value) {
            const count = product.options.length - 1;
            window.estadoConfiguracionIncluidos(count
                ? `${category.selectedOptions[0].textContent}: ${count} ${count === 1 ? 'producto disponible' : 'productos disponibles'}. Elige uno para ver sus cupos.`
                : 'Esta categoría no tiene productos activos para configurar.');
        } else {
            window.estadoConfiguracionIncluidos(`Estás configurando ${product.selectedOptions[0].textContent}. Los cupos se aplican solo a este producto.`);
        }
    };
    window.bloquearConfiguracionIncluidos = value => {
        saving = value;
        pickers.forEach(picker => {
            closePicker(picker);
            picker.querySelector('.config-picker-trigger').disabled = saving || selectOf(picker).options.length < 2;
        });
        const hasProduct = Boolean(document.getElementById('incluidos-producto').value);
        dialog.querySelectorAll('.config-stepper input').forEach(input => { input.disabled = saving || !hasProduct; });
        const button = dialog.querySelector('button[type="submit"]');
        button.textContent = saving ? 'Guardando…' : 'Guardar configuración';
        button.disabled = saving || !hasProduct;
        dialog.querySelector('form').setAttribute('aria-busy', String(saving));
        syncSteppers();
    };
    window.cerrarConfiguracionIncluidos = () => dialog.close();
    dialog.addEventListener('close', () => pickers.forEach(picker => closePicker(picker)));
    dialog.addEventListener('cancel', event => {
        const open = pickers.find(picker => !picker.querySelector('.config-picker-panel').hidden);
        if (open) { event.preventDefault(); closePicker(open, true); }
    });
    pickers.forEach(picker => {
        const trigger = picker.querySelector('.config-picker-trigger');
        trigger.addEventListener('click', () => {
            if (trigger.getAttribute('aria-expanded') === 'true') closePicker(picker);
            else openPicker(picker);
        });
        trigger.addEventListener('keydown', event => {
            if (['ArrowDown', 'ArrowUp'].includes(event.key)) {
                event.preventDefault(); openPicker(picker, true);
            }
        });
        picker.querySelector('.config-picker-search').addEventListener('input', () => renderOptions(picker));
        picker.addEventListener('keydown', event => {
            if (event.key === 'Escape' && trigger.getAttribute('aria-expanded') === 'true') {
                event.preventDefault(); event.stopPropagation(); closePicker(picker, true); return;
            }
            if (trigger.getAttribute('aria-expanded') !== 'true' || event.target === trigger) return;
            const buttons = visibleOptions(picker);
            if (event.key === 'Enter' && event.target.classList.contains('config-picker-search')) {
                event.preventDefault(); buttons[0]?.click(); return;
            }
            const index = buttons.indexOf(document.activeElement);
            let next;
            if (event.key === 'ArrowDown') next = Math.min(index + 1, buttons.length - 1);
            else if (event.key === 'ArrowUp') next = Math.max(index - 1, 0);
            else if (event.key === 'Home' && event.target.tagName === 'BUTTON') next = 0;
            else if (event.key === 'End' && event.target.tagName === 'BUTTON') next = buttons.length - 1;
            if (next !== undefined) { event.preventDefault(); buttons[next]?.focus(); }
        });
        picker.addEventListener('focusout', event => {
            if (!picker.contains(event.relatedTarget)) closePicker(picker);
        });
    });
    dialog.addEventListener('click', event => {
        pickers.forEach(picker => { if (!picker.contains(event.target)) closePicker(picker); });
    });
    function edited() {
        syncSteppers();
        window.estadoConfiguracionIncluidos('Cambios pendientes. Guarda la configuración para aplicarlos a este producto.');
    }
    dialog.querySelectorAll('.config-stepper input').forEach(input => input.addEventListener('input', edited));
    dialog.querySelectorAll('[data-step]').forEach(button => {
        button.addEventListener('click', () => {
            const input = document.getElementById(button.dataset.input);
            input.value = Math.max(0, Math.min(25, Number(input.value || 0) + Number(button.dataset.step)));
            input.dispatchEvent(new Event('input', { bubbles: true }));
        });
    });
})();
