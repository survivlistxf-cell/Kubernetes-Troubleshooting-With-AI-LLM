import { state } from './state.js';
import { escapeHtml } from './utils.js';
import { addAttachment } from './attachments.js';

// Minimal resource picker implementation (pods/nodes)
let pickerKind = null;
let pickerItems = [];

function getPickerEls() {
  return {
    modal: document.getElementById('resource-picker-modal'),
    overlay: document.getElementById('resource-picker-overlay'),
    close: document.getElementById('resource-picker-close'),
    cancel: document.getElementById('resource-picker-cancel'),
    add: document.getElementById('resource-picker-add'),
    title: document.getElementById('resource-picker-title'),
    list: document.getElementById('resource-picker-list'),
    selectAll: document.getElementById('resource-picker-select-all-cb'),
    count: document.getElementById('resource-picker-count'),
  };
}

function openModal() {
  const { modal, overlay } = getPickerEls();
  if (modal) modal.style.display = 'flex';
  if (overlay) overlay.style.display = 'block';
}

function closeModal() {
  const { modal, overlay } = getPickerEls();
  if (modal) modal.style.display = 'none';
  if (overlay) overlay.style.display = 'none';
  pickerKind = null;
  pickerItems = [];
}

function renderList() {
  const { list, count, selectAll } = getPickerEls();
  if (!list) return;

  list.innerHTML = pickerItems.map((it, idx) => {
    const label = pickerKind === 'pods'
      ? `${it.namespace || 'default'}/${it.name || 'pod'}`
      : `${it.name || 'node'}`;
    return `
      <label class="resource-picker-row">
        <input type="checkbox" data-idx="${idx}" checked />
        <span>${escapeHtml(label)}</span>
      </label>
    `;
  }).join('');

  const selected = pickerItems.length;
  if (count) count.textContent = `${selected} selected`;

  if (selectAll && !selectAll._bound) {
    selectAll._bound = true;
    selectAll.addEventListener('change', () => {
      const on = selectAll.checked;
      list.querySelectorAll('input[type="checkbox"]').forEach(cb => cb.checked = on);
      updateCount();
    });
  }

  list.querySelectorAll('input[type="checkbox"]').forEach(cb => {
    cb.addEventListener('change', updateCount);
  });
}

function updateCount() {
  const { list, count, selectAll } = getPickerEls();
  if (!list || !count) return;
  const cbs = Array.from(list.querySelectorAll('input[type="checkbox"]'));
  const checked = cbs.filter(cb => cb.checked).length;
  count.textContent = `${checked} selected`;
  if (selectAll) selectAll.checked = checked === cbs.length;
}

export function openResourcePicker(kind) {
  pickerKind = kind;
  const data = kind === 'pods' ? state.lastScannedPods : state.lastScannedNodes;
  if (!Array.isArray(data) || data.length === 0) {
    alert(kind === 'pods' ? 'No pods scanned yet.' : 'No nodes scanned yet.');
    return;
  }

  pickerItems = data.slice(0, 200);
  const { title, selectAll } = getPickerEls();
  if (title) title.textContent = kind === 'pods' ? 'Select pods to attach' : 'Select nodes to attach';
  if (selectAll) selectAll.checked = true;

  renderList();
  openModal();
}

export function initResourcePicker() {
  const { close, cancel, add, overlay, list } = getPickerEls();
  close?.addEventListener('click', closeModal);
  cancel?.addEventListener('click', closeModal);
  overlay?.addEventListener('click', closeModal);

  add?.addEventListener('click', () => {
    const { list } = getPickerEls();
    if (!list) return;
    const cbs = Array.from(list.querySelectorAll('input[type="checkbox"]'));
    const selected = cbs
      .filter(cb => cb.checked)
      .map(cb => pickerItems[Number(cb.getAttribute('data-idx'))])
      .filter(Boolean);

    if (!selected.length) {
      closeModal();
      return;
    }

    // Create a readable attachment per kind (single combined file)
    if (pickerKind === 'pods') {
      let text = `=== SELECTED PODS (${selected.length}) ===\n`;
      selected.forEach(p => {
        text += `\nPod: ${p.name}\n`;
        text += `  Namespace: ${p.namespace || 'default'}\n`;
        text += `  Status: ${p.status || 'N/A'}\n`;
        text += `  Node: ${p.node || 'N/A'}\n`;
        text += `  Ready: ${p.ready || 'N/A'}\n`;
        text += `  Restarts: ${p.restarts ?? 'N/A'}\n`;
        text += `  Age: ${p.age || 'N/A'}\n`;
        text += `  Containers: ${p.containers || 'N/A'}\n`;
      });
      addAttachment({ name: 'k8s-selected-pods.txt', size: text.length, type: 'text/plain', content: text, _scanContext: true });
    } else {
      let text = `=== SELECTED NODES (${selected.length}) ===\n`;
      selected.forEach(n => {
        text += `\nNode: ${n.name}\n`;
        text += `  Status: ${n.status || 'N/A'}\n`;
        text += `  Roles: ${n.roles || 'N/A'}\n`;
        text += `  Age: ${n.age || 'N/A'}\n`;
        text += `  Version: ${n.version || 'N/A'}\n`;
        text += `  Internal IP: ${n.internalIp || 'N/A'}\n`;
        text += `  External IP: ${n.externalIp || 'N/A'}\n`;
      });
      addAttachment({ name: 'k8s-selected-nodes.txt', size: text.length, type: 'text/plain', content: text, _scanContext: true });
    }

    closeModal();
  });
}
