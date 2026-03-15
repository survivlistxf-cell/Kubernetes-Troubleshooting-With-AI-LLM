import { state } from './state.js';
import { escapeHtml, formatFileSize } from './utils.js';
import { openAttachmentPreviewFromFile, openAttachmentPreviewFromId } from './modals.js';
import { switchToTab } from './navigation.js';
import { openResourcePicker } from './resourcePicker.js';

export function getFileIcon(name) {
  const ext = (String(name || '').split('.').pop() || '').toLowerCase();
  const map = {
    'txt': '📄', 'log': '📄', 'md': '📝',
    'json': '🧾', 'yaml': '🧾', 'yml': '🧾',
    'js': '🟨', 'ts': '🟦', 'tsx': '🟦', 'jsx': '🟦',
    'py': '🐍', 'java': '☕', 'go': '🐹', 'rs': '🦀',
    'sh': '💻', 'c': '📄', 'cpp': '📄', 'h': '📄'
  };
  return map[ext] || '📎';
}

function readFileAsText(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || ''));
    reader.onerror = () => reject(reader.error || new Error('Failed to read file'));
    reader.readAsText(file);
  });
}

function toggleAttachDropdown(forceClose = false) {
  const attachBtn = document.getElementById('attach-btn');
  const attachDropdown = document.getElementById('attach-dropdown');
  const attachScanOption = document.getElementById('attach-scan-option');

  if (!attachDropdown) return;
  const isOpen = attachDropdown.style.display !== 'none';
  if (forceClose || isOpen) {
    attachDropdown.style.display = 'none';
    attachBtn?.classList.remove('open');
  } else {
    const hasScanData = (Array.isArray(state.lastScannedPods) && state.lastScannedPods.length > 0)
      || (Array.isArray(state.lastScannedNodes) && state.lastScannedNodes.length > 0);

    if (attachScanOption) {
      attachScanOption.disabled = !hasScanData;
      const desc = attachScanOption.querySelector('.attach-dropdown-desc');
      if (desc) {
        desc.textContent = hasScanData
          ? `${state.lastScannedPods?.length || 0} pods, ${state.lastScannedNodes?.length || 0} nodes available`
          : 'Scan pods or nodes first';
      }
    }

    attachDropdown.style.display = 'block';
    attachBtn?.classList.add('open');
  }
}

function buildScanContextText() {
  const hasPods = Array.isArray(state.lastScannedPods) && state.lastScannedPods.length > 0;
  const hasNodes = Array.isArray(state.lastScannedNodes) && state.lastScannedNodes.length > 0;
  if (!hasPods && !hasNodes) return null;

  let contextContent = '';

  if (hasPods) {
    contextContent += `=== PODS SCAN (${state.lastScannedPods.length} pods) ===\n`;
    state.lastScannedPods.forEach(p => {
      contextContent += `\nPod: ${p.name}\n`;
      contextContent += `  Namespace: ${p.namespace || 'default'}\n`;
      contextContent += `  Status: ${p.status || 'N/A'}\n`;
      contextContent += `  Node: ${p.node || 'N/A'}\n`;
      contextContent += `  Ready: ${p.ready || 'N/A'}\n`;
      contextContent += `  Restarts: ${p.restarts ?? 'N/A'}\n`;
      contextContent += `  Age: ${p.age || 'N/A'}\n`;
      contextContent += `  Containers: ${p.containers || 'N/A'}\n`;
    });
  }

  if (hasNodes) {
    if (contextContent) contextContent += '\n';
    contextContent += `=== NODES SCAN (${state.lastScannedNodes.length} nodes) ===\n`;
    state.lastScannedNodes.forEach(n => {
      contextContent += `\nNode: ${n.name}\n`;
      contextContent += `  Status: ${n.status || 'N/A'}\n`;
      contextContent += `  Roles: ${n.roles || 'N/A'}\n`;
      contextContent += `  Age: ${n.age || 'N/A'}\n`;
      contextContent += `  Version: ${n.version || 'N/A'}\n`;
      contextContent += `  Internal IP: ${n.internalIp || 'N/A'}\n`;
      contextContent += `  External IP: ${n.externalIp || 'N/A'}\n`;
    });
  }

  return contextContent;
}

export function addAttachment(fileObj) {
  state.attachedFiles.push(fileObj);
  updateAttachmentsPreview();
}

export function clearDraftAttachments() {
  state.attachedFiles = [];
  updateAttachmentsPreview();
}

function removeAttachment(idx) {
  state.attachedFiles.splice(idx, 1);
  updateAttachmentsPreview();
}

export function updateAttachmentsPreview() {
  const attachmentsPreview = document.getElementById('attachments-preview');
  if (!attachmentsPreview) return;

  if (!Array.isArray(state.attachedFiles) || state.attachedFiles.length === 0) {
    attachmentsPreview.innerHTML = '';
    return;
  }

  attachmentsPreview.innerHTML = state.attachedFiles.map((f, i) => {
    const icon = f?._scanContext ? '🔍' : getFileIcon(f?.name);
    const name = escapeHtml(f?.name || 'attachment');
    const size = typeof f?.size === 'number' ? escapeHtml(formatFileSize(f.size)) : '';
    return `
      <div class="attachment-pill" data-idx="${i}">
        <span class="attachment-icon">${icon}</span>
        <span class="attachment-name" title="${name}">${name}</span>
        ${size ? `<span class="attachment-size">${size}</span>` : ``}
        <button class="attachment-open" type="button" title="Preview">👁️</button>
        <button class="attachment-remove" type="button" title="Remove">✕</button>
      </div>
    `;
  }).join('');

  // Bind buttons (simple because pills rerender)
  attachmentsPreview.querySelectorAll('.attachment-pill').forEach(pill => {
    const idx = Number(pill.getAttribute('data-idx'));
    pill.querySelector('.attachment-remove')?.addEventListener('click', () => removeAttachment(idx));
    pill.querySelector('.attachment-open')?.addEventListener('click', () => openAttachmentPreviewFromFile(state.attachedFiles[idx]));
    // click on pill also previews
    pill.addEventListener('click', (e) => {
      if (e.target?.closest?.('button')) return;
      openAttachmentPreviewFromFile(state.attachedFiles[idx]);
    });
  });
}

export function initAttachments() {
  const attachBtn = document.getElementById('attach-btn');
  const attachDropdown = document.getElementById('attach-dropdown');
  const attachFileOption = document.getElementById('attach-file-option');
  const attachScanOption = document.getElementById('attach-scan-option');
  const fileInput = document.getElementById('file-input');
  const attachBrowsePodsOption = document.getElementById('attach-browse-pods-option');
  const attachBrowseNodesOption = document.getElementById('attach-browse-nodes-option');

  attachBtn?.addEventListener('click', (e) => {
    e.stopPropagation();  //dai click pe ceva din meniu si nu ajunge clickul si la document
    //mai jos, facem sa se inchida daca dam click pe ecran (linia 161) 
    toggleAttachDropdown(false);
  });

  document.addEventListener('click', (e) => {
    if (!attachDropdown || attachDropdown.style.display === 'none') return;
    const wrapper = e.target.closest('.attach-dropdown-wrapper');
    if (!wrapper) toggleAttachDropdown(true);
  });

  attachFileOption?.addEventListener('click', () => {
    toggleAttachDropdown(true);
    fileInput?.click();
  });

  attachScanOption?.addEventListener('click', () => {
    toggleAttachDropdown(true);
    const text = buildScanContextText();
    if (!text) return;

    addAttachment({
      name: 'k8s-scan-context.txt',
      size: text.length,
      type: 'text/plain',
      content: text,
      _scanContext: true,
    });
  });

  attachBrowsePodsOption?.addEventListener('click', () => {
    toggleAttachDropdown(true);
    openResourcePicker('pods');
  });

  attachBrowseNodesOption?.addEventListener('click', () => {
    toggleAttachDropdown(true);
    openResourcePicker('nodes');
  });

  fileInput?.addEventListener('change', async (e) => {
    const files = Array.from(e.target.files || []);
    if (!files.length) return;

    for (const f of files) {
      const content = await readFileAsText(f);
      addAttachment({
        name: f.name,
        size: content.length,
        type: f.type || 'text/plain',
        content,
      });
    }
    // reset input so selecting same file again triggers change
    fileInput.value = '';
  });

  updateAttachmentsPreview();
}

// Used by chat/history chips
export function bindChatAttachmentClicks(messagesArea) {
  //verificam daca am atasat deja event listenerul
  //pentru a fi eficient, punem event listenerul o singura data pe tot messageArea
  //si il reutilizam de fiecare data cand adaugam fisiere
  if (!messagesArea || messagesArea._chatAttachBound) return;
  messagesArea._chatAttachBound = true;

  messagesArea.addEventListener('click', async (e) => {
    const chip = e.target?.closest?.('.chat-attachment-chip');
    if (!chip) return;

    //fisier din baza de date
    //fisierele au attId doar daca sunt din db
    const attId = chip.getAttribute('data-att-id');
    if (attId) {
      await openAttachmentPreviewFromId(attId);
      return;
    }

    //fisier din cache (abia trimis)
    const msgId = chip.getAttribute('data-msg');
    const idx = Number(chip.getAttribute('data-idx') || '0');
    const files = state.sentMessageAttachments.get(msgId);
    if (!files || !files[idx]) return;
    openAttachmentPreviewFromFile(files[idx]);
  });
}

//randeaza atasamentele in chat, sub inputul de text
export function renderChatAttachmentsHtml(files, messageId, isServerMeta = false) {
  if (!Array.isArray(files) || files.length === 0) return '';
  const chips = files.map((f, idx) => {
    const name = escapeHtml((f.name || f.fileName || 'attachment'));
    const type = escapeHtml((f.type || f.mimeType || 'text/plain'));
    const sizeVal = (typeof f.size === 'number') ? f.size : (typeof f.sizeBytes === 'number' ? f.sizeBytes : null);
    const size = sizeVal != null ? escapeHtml(formatFileSize(sizeVal)) : '';
    const icon = (f._scanContext ? '🔍' : getFileIcon(f.name || f.fileName));
    const attId = isServerMeta ? (f.id != null ? String(f.id) : '') : '';
    return `
      <div class="chat-attachment-chip" data-msg="${messageId}" data-idx="${idx}" data-att-id="${attId}" title="Click to preview">
        <span class="chat-attachment-icon">${icon}</span>
        <span class="chat-attachment-name">${name}</span>
        ${size ? `<span class="chat-attachment-size">${size}</span>` : ``}
      </div>
    `;
  }).join('');
  return `<div class="chat-attachments">${chips}</div>`;
}
