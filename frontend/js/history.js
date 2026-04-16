import { API_URL, state } from './state.js';
import { escapeHtml, formatTimestamp, deriveTitleFrom } from './utils.js';
import { getConversations, getConversationMessages } from './api.js';
import { openDeleteModal, openTitleModal } from './modals.js';
import { switchToTab, hideWelcomeHeader } from './navigation.js';
import { addMessage, addUserMessageWithAttachments } from './chat.js';
import { renderChatAttachmentsHtml } from './attachments.js';

const selectedConversations = new Set();
let isHistoryToolbarBound = false;
let isSelectMode = false;

export function toggleSelectMode(force) {
    if (typeof force === 'boolean') {
        isSelectMode = force;
    } else {
        isSelectMode = !isSelectMode;
    }
    
    selectedConversations.clear();
    updateBulkDeleteUI();
    
    const btn = document.getElementById('chat-history-enter-select-mode');
    if (btn) btn.textContent = isSelectMode ? 'Cancel' : 'Select';
    
    const toolbar = document.getElementById('chat-history-toolbar');
    if (toolbar) toolbar.style.display = isSelectMode ? 'flex' : 'none';
    
    const cbs = document.querySelectorAll('.conv-checkbox-wrapper');
    cbs.forEach(cb => {
        cb.style.display = isSelectMode ? 'flex' : 'none';
        const input = cb.querySelector('input');
        if (input) input.checked = false;
    });
}
window.toggleSelectMode = toggleSelectMode;

function updateBulkDeleteUI() {
  const deleteBtn = document.getElementById('chat-history-delete-selected');
  const selectAllCb = document.getElementById('chat-history-select-all');
  if (deleteBtn) {
    if (selectedConversations.size > 0) {
      deleteBtn.textContent = `Delete selected (${selectedConversations.size})`;
      deleteBtn.disabled = false;
    } else {
      deleteBtn.textContent = 'Delete selected (0)';
      deleteBtn.disabled = true;
    }
  }
  if (selectAllCb) {
    const allCbs = document.querySelectorAll('.conv-checkbox');
    if (allCbs.length > 0 && selectedConversations.size === allCbs.length) {
      selectAllCb.checked = true;
    } else {
      selectAllCb.checked = false;
    }
  }
}



function renderConversationList(container, conversations) {
  if (!container) return;

  if (!Array.isArray(conversations) || conversations.length === 0) {
    container.innerHTML = '<p style="text-align: center; opacity: 0.7; margin-top: 2rem;">No conversations yet</p>';
    return;
  }

  const safe = conversations.slice(0, 200);
  container.innerHTML = safe.map(c => {
    const conversationId = String(c.conversationId || c.conversation_id || '');
    const title = String(c.title || deriveTitleFrom(c) || conversationId.slice(0, 8) + '...');
    const lastUpdated = formatTimestamp(c.lastUpdated || c.last_updated || c.createdAt || c.created_at);

    return `
      <div class="conversation-item" data-conversation-id="${escapeHtml(conversationId)}" style="display:flex;align-items:center;justify-content:space-between;border:1px solid rgba(255,255,255,0.08);border-radius:10px;padding:10px;margin:8px 0;background:rgba(0,0,0,0.06);cursor:pointer;">
        <div class="conv-checkbox-wrapper" style="margin-right: 12px; display:none; align-items:center;">
            <input type="checkbox" class="conv-checkbox" value="${escapeHtml(conversationId)}" style="cursor:pointer; width: 1.1rem; height: 1.1rem;" />
        </div>
        <div style="flex:1;min-width:0;">
          <div style="font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">${escapeHtml(title)}</div>
          <div style="opacity:0.65;font-size:0.85rem;margin-top:6px;">${escapeHtml(lastUpdated)}</div>
        </div>
        <div style="margin-left:12px;display:flex;align-items:center;gap:8px;">
          <button class="resume-btn action-icon-btn" title="Resume" aria-label="Resume" style="border:1px solid rgba(255,255,255,0.06);background:transparent;color:#fff;">▶️</button>
          <button class="edit-title action-icon-btn" title="Edit title" aria-label="Edit title" style="border:1px solid rgba(255,255,255,0.06);background:transparent;color:#fff;">✏️</button>
          <button class="conversation-options action-icon-btn delete" title="Delete" aria-label="Delete" style="border:1px solid rgba(255,255,255,0.06);background:transparent;color:#fff;">🗑️</button>
        </div>
      </div>
    `;
  }).join('');

  container.querySelectorAll('.conversation-item').forEach(el => {
    const cb = el.querySelector('.conv-checkbox');
    if (cb) {
        cb.checked = selectedConversations.has(cb.value);
        cb.addEventListener('change', (ev) => {
            if (ev.target.checked) selectedConversations.add(ev.target.value);
            else selectedConversations.delete(ev.target.value);
            updateBulkDeleteUI();
        });
        cb.addEventListener('click', (ev) => ev.stopPropagation());
    }

    async function resumeConversation(conversationId) {
      if (!conversationId) return;

      localStorage.setItem(state.conversationIdKey, String(conversationId));
      switchToTab('home');
      hideWelcomeHeader();

      const userId = localStorage.getItem('userId');
      if (!userId) return;

      const { resp, data } = await getConversationMessages(conversationId, userId);
      if (!resp.ok) return;

      const thread = (data?.chats || []).slice();
      const messagesArea = document.getElementById('messages');
      messagesArea.innerHTML = '';

      thread.forEach(x => {
        const um = (x.userMessage ?? x.user_message ?? '').toString();
        const ar = (x.aiResponse ?? x.ai_response ?? '').toString();
        if (um) {
          const atts = Array.isArray(x.attachments) ? x.attachments : [];
          if (atts.length) addUserMessageWithAttachments(um, atts, true);
          else addMessage(um, 'user');
        }
        if (ar) addMessage(ar, 'assistant', x.feedback || 0);
      });
    }

    el.addEventListener('click', async (ev) => {
      const target = ev.target;
      
      if (isSelectMode) {
          if (!target.classList.contains('conv-checkbox')) {
              const cb = el.querySelector('.conv-checkbox');
              if (cb) {
                  cb.checked = !cb.checked;
                  cb.dispatchEvent(new Event('change'));
              }
          }
          return;
      }
      
      if (target && (target.classList.contains('conversation-options') || target.classList.contains('resume-btn') || target.classList.contains('edit-title') || target.classList.contains('conv-checkbox'))) {
        return;
      }
      const convId = el.getAttribute('data-conversation-id');
      await resumeConversation(convId);
    });

    el.querySelector('.resume-btn')?.addEventListener('click', async (ev) => {
      ev.stopPropagation();
      const convId = el.getAttribute('data-conversation-id');
      await resumeConversation(convId);
    });

    el.querySelector('.conversation-options')?.addEventListener('click', (ev) => {
      ev.stopPropagation();
      const convId = el.getAttribute('data-conversation-id');
      if (!convId) return;
      openDeleteModal({ conversationId: convId });
    });

    el.querySelector('.edit-title')?.addEventListener('click', (ev) => {
      ev.stopPropagation();
      const userId = localStorage.getItem('userId');
      if (!userId) { alert('Login required'); return; }
      const convId = el.getAttribute('data-conversation-id');
      if (!convId) return;
      const current = el.querySelector('div[style*="flex:1"] > div')?.textContent || '';
      openTitleModal({ conversationId: convId, currentTitle: current, userId });
    });
  });
}

export async function loadChatHistoryIntoTab() {
  const container = document.getElementById('chat-history-content');
  if (!container) return;
  
  isSelectMode = false;
  const mainBtn = document.getElementById('chat-history-enter-select-mode');
  if (mainBtn) mainBtn.textContent = 'Select';

  const toolbar = document.getElementById('chat-history-toolbar');
  if (!isHistoryToolbarBound && toolbar) {
      const selectAllCb = document.getElementById('chat-history-select-all');
      const deleteBtn = document.getElementById('chat-history-delete-selected');
      const selectBtn = document.getElementById('chat-history-enter-select-mode');
      
      if (selectBtn) {
          selectBtn.addEventListener('click', toggleSelectMode);
      }
      
      if (selectAllCb) {
        selectAllCb.addEventListener('change', (ev) => {
           const cbs = document.querySelectorAll('.conv-checkbox');
           if (ev.target.checked) {
              cbs.forEach(cb => { cb.checked = true; selectedConversations.add(cb.value); });
           } else {
              cbs.forEach(cb => { cb.checked = false; });
              selectedConversations.clear();
           }
           updateBulkDeleteUI();
        });
      }
      if (deleteBtn) {
        deleteBtn.addEventListener('click', () => {
           if (selectedConversations.size > 0) {
               openDeleteModal({ bulkIds: Array.from(selectedConversations) });
           }
        });
      }
      isHistoryToolbarBound = true;
  }
  
  selectedConversations.clear();
  updateBulkDeleteUI();

  const userId = localStorage.getItem('userId');
  if (!userId) {
    if (toolbar) toolbar.style.display = 'none';
    const selectBtn = document.getElementById('chat-history-enter-select-mode');
    if (selectBtn) selectBtn.style.display = 'none';
    container.innerHTML = '<p style="text-align: center; opacity: 0.7; margin-top: 2rem;">Login to see your chat history.</p>';
    return;
  }

  container.innerHTML = '<p style="text-align: center; opacity: 0.7; margin-top: 2rem;">Loading...</p>';

  const { resp, data } = await getConversations(userId);
  if (!resp.ok) {
    const msg = data?.message || data?.error || 'Failed to load chat history';
    container.innerHTML = `<p style="text-align: center; color: #d32f2f; margin-top: 2rem;">${escapeHtml(msg)}</p>`;
    return;
  }

  renderConversationList(container, data.conversations || []);
  
  const selectBtn = document.getElementById('chat-history-enter-select-mode');
  if (data.conversations && data.conversations.length > 0) {
     if (selectBtn) {
         selectBtn.style.display = 'inline-block';
     }
  } else {
     isSelectMode = false;
     if (selectBtn) selectBtn.style.display = 'none';
     if (toolbar) toolbar.style.display = 'none';
  }
  
  if (isSelectMode) {
     const cbs = document.querySelectorAll('.conv-checkbox-wrapper');
     cbs.forEach(cb => cb.style.display = 'flex');
     if (toolbar) toolbar.style.display = 'flex';
  }
}
