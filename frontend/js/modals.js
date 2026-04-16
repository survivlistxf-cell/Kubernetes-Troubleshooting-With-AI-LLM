import { state } from './state.js';
import { escapeHtml, formatFileSize } from './utils.js';
import { deleteConversation, patchConversationTitle, fetchAttachmentContent } from './api.js';
import { loadChatHistoryIntoTab } from './history.js';
import { startNewConversation, switchToTab } from './navigation.js';

// ----- Title modal -----
let titleModalState = { conversationId: null, userId: null };

export function openTitleModal({ conversationId, currentTitle, userId }) {
  closeDeleteModal();
  const modal = document.getElementById('title-modal');
  const overlay = document.getElementById('title-modal-overlay');
  const input = document.getElementById('title-modal-input');
  if (!modal || !overlay || !input) return;

  titleModalState = { conversationId, userId };
  input.value = String(currentTitle || '').trim();
  modal.style.display = 'flex';
  overlay.style.display = 'block';
  setTimeout(() => input.focus(), 0);
}

export function closeTitleModal() {
  const modal = document.getElementById('title-modal');
  const overlay = document.getElementById('title-modal-overlay');
  if (modal) modal.style.display = 'none';
  if (overlay) overlay.style.display = 'none';
  titleModalState = { conversationId: null, userId: null };
}

async function submitTitleModal() {
  const input = document.getElementById('title-modal-input');
  const saveBtn = document.getElementById('title-modal-save');
  if (!input) return;
  const trimmed = String(input.value || '').trim();
  if (!trimmed) return;

  const { conversationId, userId } = titleModalState;
  if (!conversationId || !userId) {
    closeTitleModal();
    return;
  }

  try {
    if (saveBtn) saveBtn.disabled = true;
    const { resp, data } = await patchConversationTitle(conversationId, userId, trimmed);
    if (!resp.ok) {
      alert('Failed to update title: ' + (data?.message || data?.error || resp.statusText));
      return;
    }
    closeTitleModal();
    loadChatHistoryIntoTab();
  } catch (e) {
    alert('Failed to update title: ' + (e?.message || e));
  } finally {
    if (saveBtn) saveBtn.disabled = false;
  }
}

// ----- Delete modal -----
let deleteModalState = { conversationId: null, bulkIds: null };

export function openDeleteModal({ conversationId = null, bulkIds = null }) {
  closeTitleModal();
  const modal = document.getElementById('delete-modal');
  const overlay = document.getElementById('delete-modal-overlay');
  if (!modal || !overlay) return;
  
  deleteModalState = { conversationId, bulkIds };
  
  const title = modal.querySelector('h2');
  const p = modal.querySelector('.auth-form p');
  
  if (bulkIds && bulkIds.length > 0) {
      if (title) title.textContent = 'Bulk Delete Conversations';
      if (p) p.textContent = `Are you sure you want to delete ${bulkIds.length} conversations? This cannot be undone.`;
  } else {
      if (title) title.textContent = 'Delete conversation';
      if (p) p.textContent = 'Are you sure you want to delete this conversation? This cannot be undone.';
  }
  
  modal.style.display = 'flex';
  overlay.style.display = 'block';
}

export function closeDeleteModal() {
  const modal = document.getElementById('delete-modal');
  const overlay = document.getElementById('delete-modal-overlay');
  if (modal) modal.style.display = 'none';
  if (overlay) overlay.style.display = 'none';
  deleteModalState = { conversationId: null };
}

async function confirmDeleteModal() {
  const { conversationId, bulkIds } = deleteModalState;
  if (!conversationId && (!bulkIds || bulkIds.length === 0)) {
    closeDeleteModal();
    return;
  }

  const confirmBtn = document.getElementById('delete-modal-confirm');
  if (confirmBtn) confirmBtn.disabled = true;

  try {
    if (bulkIds && bulkIds.length > 0) {
      // Bulk delete path
      const results = await Promise.all(bulkIds.map(id => deleteConversation(id)));
      
      const currentConv = localStorage.getItem(state.conversationIdKey);
      if (currentConv && bulkIds.includes(String(currentConv))) {
        startNewConversation({ switchToHome: false });
        switchToTab('home');
      }
    } else {
      // Single delete path
      const { resp, data } = await deleteConversation(conversationId);
      if (!resp.ok) {
        alert('Failed to delete conversation: ' + (data?.error || data?.message || resp.statusText));
        if (confirmBtn) confirmBtn.disabled = false;
        return;
      }

      const currentConv = localStorage.getItem(state.conversationIdKey);
      if (currentConv && String(currentConv) === String(conversationId)) {
        startNewConversation({ switchToHome: false });
        switchToTab('home');
      }
    }

    closeDeleteModal();
    loadChatHistoryIntoTab();
  } catch (e) {
    console.error('Delete failed', e);
    alert('Failed to delete: ' + (e?.message || e));
  } finally {
    if (confirmBtn) confirmBtn.disabled = false;
  }
}

// ----- Attachment preview modal -----
export function ensureAttachmentPreviewModal() {
  const modal = document.getElementById('attachment-preview-modal');
  if (!modal) return null;

  const closeBtn = document.getElementById('attachment-preview-close');
  const backdrop = modal.querySelector('.attachment-preview-backdrop');

  function close() {
    modal.classList.add('hidden');
    modal.setAttribute('aria-hidden', 'true');
    document.body.style.overflow = '';
  }

  if (closeBtn && !closeBtn._bound) {
    closeBtn._bound = true; // prevenim adaugarea multipla de event listeners la reapelarea functiei
    closeBtn.addEventListener('click', close);
  }
  if (backdrop && !backdrop._bound) {
    backdrop._bound = true; // prevenim adaugarea multipla de event listeners la reapelarea functiei
    backdrop.addEventListener('click', close);
  }
  if (!document._attachmentPreviewEscBound) {
    document._attachmentPreviewEscBound = true; // prevenim adaugarea multipla de event listeners la reapelarea functiei
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') close();
    });
  }

  return { modal, close };
}

export function formatAttachmentContentForPreview(file) {
  const name = file?.name || 'attachment';
  const type = (file?.type || '').toLowerCase();
  const ext = (name.split('.').pop() || '').toLowerCase();

  let content = (file && typeof file.content === 'string') ? file.content : '';

  if (type.includes('json') || ext === 'json') {
    try {
      const parsed = JSON.parse(content);
      content = JSON.stringify(parsed, null, 2);
    } catch (_) { }
  }
  return content;
}

export function openAttachmentPreviewFromFile(file) {
  const helpers = ensureAttachmentPreviewModal();
  if (!helpers) return;

  const { modal } = helpers;

  const titleEl = document.getElementById('attachment-preview-title');
  const metaEl = document.getElementById('attachment-preview-meta');
  const contentEl = document.getElementById('attachment-preview-content');

  if (titleEl) titleEl.textContent = file?.name || 'Attachment';
  if (metaEl) {
    const size = typeof file?.size === 'number' ? formatFileSize(file.size) : '';
    const parts = [
      file?.type ? `Type: ${file.type}` : null,
      size ? `Size: ${size}` : null,
      file?._scanContext ? 'Source: scan-context' : null,
      file?._kdiagPayload ? 'Protocol: kdiag' : null
    ].filter(Boolean);  // eliminam valorile nule sau false
    metaEl.textContent = parts.join(' • ');
  }
  if (contentEl) {
    const preview = formatAttachmentContentForPreview(file);  // doar json parsing deocamdata
    contentEl.textContent = preview || '(No preview available for this attachment)';
  }

  modal.classList.remove('hidden');
  modal.setAttribute('aria-hidden', 'false');
  document.body.style.overflow = 'hidden';
}

// Fetch attachment content by id (history)
export async function openAttachmentPreviewFromId(attId) {
  if (!attId) return;
  if (state.attachmentContentCache.has(attId)) {   // prima data cautam in state-ul browserului (cache)
    const entry = state.attachmentContentCache.get(attId);
    return openAttachmentPreviewFromFile({ name: entry.name, type: entry.type, size: entry.size, content: entry.content });
  }

  const { resp, data } = await fetchAttachmentContent(attId); // daca nu e in cache luam din db
  if (!resp.ok) return;

  const entry = { content: data?.content || '', name: data?.name, type: data?.type, size: data?.size };
  state.attachmentContentCache.set(attId, entry); // si il adaugam in cache
  openAttachmentPreviewFromFile({ name: entry.name || 'attachment', type: entry.type || 'text/plain', size: entry.size || 0, content: entry.content });
}

// ----- bind modal buttons -----
export function initModals() {
  // Title modal
  document.getElementById('title-modal-close')?.addEventListener('click', closeTitleModal);
  document.getElementById('title-modal-cancel')?.addEventListener('click', closeTitleModal);
  document.getElementById('title-modal-form')?.addEventListener('submit', (ev) => {
    ev.preventDefault();
    submitTitleModal();
  });

  // Delete modal
  document.getElementById('delete-modal-close')?.addEventListener('click', closeDeleteModal);
  document.getElementById('delete-modal-cancel')?.addEventListener('click', closeDeleteModal);
  document.getElementById('delete-modal-confirm')?.addEventListener('click', confirmDeleteModal);

  // ESC handler for action modals
  document.addEventListener('keydown', (ev) => {
    if (ev.key !== 'Escape') return;
    const titleOpen = document.getElementById('title-modal')?.style?.display && document.getElementById('title-modal')?.style?.display !== 'none';
    const deleteOpen = document.getElementById('delete-modal')?.style?.display && document.getElementById('delete-modal')?.style?.display !== 'none';
    if (titleOpen) closeTitleModal();
    if (deleteOpen) closeDeleteModal();
  });

  // Expose for index.html onclick=
  window.closeTitleModal = closeTitleModal;
  window.closeDeleteModal = closeDeleteModal;
}
