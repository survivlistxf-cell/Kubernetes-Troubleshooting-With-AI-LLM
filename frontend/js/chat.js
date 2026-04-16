import { state } from './state.js';
import { escapeHtml, getOrCreateConversationId } from './utils.js';
import { postChat } from './api.js';
import { hideWelcomeHeader, autoCollapseSidebar } from './navigation.js';
import { renderChatAttachmentsHtml, bindChatAttachmentClicks, clearDraftAttachments } from './attachments.js';
import { loadChatHistoryIntoTab } from './history.js';

// DOM refs (initialized in initChat)
let promptForm, promptInput, messagesArea;

// Typing indicator
function showTypingIndicator() {
  const typingDiv = document.createElement('div');
  typingDiv.className = 'message assistant typing-indicator';
  typingDiv.id = 'typing-indicator';

  const avatarDiv = document.createElement('div');
  avatarDiv.className = 'message-avatar';
  avatarDiv.textContent = 'AI';

  const contentDiv = document.createElement('div');
  contentDiv.className = 'message-content';
  contentDiv.innerHTML = '<span></span><span></span><span></span>';

  typingDiv.appendChild(avatarDiv);
  typingDiv.appendChild(contentDiv);
  messagesArea.appendChild(typingDiv);

  setTimeout(() => {
    messagesArea.scrollTop = messagesArea.scrollHeight;
    typingDiv.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, 50);
}

function removeTypingIndicator() {
  const typingIndicator = document.getElementById('typing-indicator');
  if (typingIndicator) typingIndicator.remove();
}

//adauga mesajul userului/AI-ului in messageArea
export function addMessage(text, sender, score = 0) {
  const messageDiv = document.createElement('div');
  messageDiv.className = `message ${sender}`;
  // We attach the message ID or conversation ID as needed. For current conv, state provides it.
  
  const avatarDiv = document.createElement('div');
  avatarDiv.className = 'message-avatar';
  avatarDiv.textContent = sender === 'user' ? 'You' : 'AI';

  const contentDiv = document.createElement('div');
  contentDiv.className = 'message-content';

  let feedbackHtml = '';
  if (sender === 'assistant') {
      feedbackHtml = `
      <div class="feedback-buttons">
          <button class="feedback-btn like-btn ${score === 1 ? 'active' : ''}" title="Helpful" aria-label="Helpful">
              <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="lucide lucide-thumbs-up"><path d="M7 10v12"/><path d="M15 5.88 14 10h5.83a2 2 0 0 1 1.92 2.56l-2.33 8A2 2 0 0 1 17.5 22H4a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2h2.76a2 2 0 0 0 1.79-1.11L12 2A3.13 3.13 0 0 1 15 5.88Z"/></svg>
          </button>
          <button class="feedback-btn dislike-btn ${score === -1 ? 'active' : ''}" title="Not helpful" aria-label="Not helpful">
              <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" class="lucide lucide-thumbs-down"><path d="M17 14V2"/><path d="M9 18.12 10 14H4.17a2 2 0 0 1-1.92-2.56l2.33-8A2 2 0 0 1 6.5 2H20a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-2.76a2 2 0 0 0-1.79 1.11L12 22a3.13 3.13 0 0 1-3-3.88Z"/></svg>
          </button>
      </div>`;
  }

  contentDiv.innerHTML = renderMarkdown(text) + feedbackHtml;
  attachCopyListeners(contentDiv);

  if (sender === 'assistant') {
      const likeBtn = contentDiv.querySelector('.like-btn');
      const dislikeBtn = contentDiv.querySelector('.dislike-btn');
      if (likeBtn && dislikeBtn) {
          likeBtn.addEventListener('click', () => submitFeedback(1, likeBtn, dislikeBtn));
          dislikeBtn.addEventListener('click', () => submitFeedback(-1, dislikeBtn, likeBtn));
      }
  }

  messageDiv.appendChild(avatarDiv);
  messageDiv.appendChild(contentDiv);
  messagesArea.appendChild(messageDiv);

  // Asigură scroll la final după ce DOM-ul e updatat
  setTimeout(() => {
    messagesArea.scrollTop = messagesArea.scrollHeight;
    messageDiv.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, 50);
}


//adauga mesajul userului in messageArea, impreuna cu atasamentele
export function addUserMessageWithAttachments(messageText, files, isServerMeta = false) {
  const messageId = `m${Date.now()}_${Math.floor(Math.random() * 1e9)}`;
  if (!isServerMeta) {
    // salveaza snapshot-ul de atasamente pentru a permite previzualizarea dupa trimitere
    // si pentru a afisa preview imediat, inainte ca serverul sa proceseze atasamentele
    // atunci cand incarc conversatia din istoric, atasamentele sunt luate din db, deci nu mai e nevoie de asta
    // isServerMeta = true => fisier din db
    // isServerMeta = false => fisier din cache
    state.sentMessageAttachments.set(messageId, files || []);
  }

  const area = messagesArea || document.getElementById('messages');

  const messageDiv = document.createElement('div');
  messageDiv.className = 'message user';

  const avatarDiv = document.createElement('div');
  avatarDiv.className = 'message-avatar';
  avatarDiv.textContent = 'You';

  const contentDiv = document.createElement('div');
  contentDiv.className = 'message-content';

  const safeText = renderMarkdown(messageText || '');
  const attachmentsHtml = renderChatAttachmentsHtml(files || [], messageId, isServerMeta);
  contentDiv.innerHTML = `${safeText || (files?.length ? '📎 Attachments' : '')}${attachmentsHtml}`;
  attachCopyListeners(contentDiv);

  messageDiv.appendChild(avatarDiv);
  messageDiv.appendChild(contentDiv);
  area.appendChild(messageDiv);

  // Asigură scroll la final după ce DOM-ul e updatat
  setTimeout(() => {
    area.scrollTop = area.scrollHeight;
    messageDiv.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, 50);
}

// ----- Markdown renderer (kept from your app.js, trimmed)
const COPY_ICON = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>`;

function makeCmdBlock(code, lang) {
  const safeCode = escapeHtml(code);
  const safeAttr = code.replace(/&/g, '&amp;').replace(/"/g, '&quot;');
  const langLabel = lang || 'bash';
  return `<div class="cmd-block-wrapper">
    <div class="cmd-block-header">
      <span class="cmd-block-lang">${escapeHtml(langLabel)}</span>
      <button class="cmd-copy-btn" data-copy-code="${safeAttr}" title="Copy code">
        ${COPY_ICON}<span class="copy-label">Copy code</span>
      </button>
    </div>
    <pre class="cmd-block-code">${safeCode}</pre>
  </div>`;
}

//afiseaza corect simbolurile din markdown 
//**text** devine <strong>text</strong>, etc
function renderMarkdown(text) {
  if (!text) return '';
  const lines = String(text).split('\n');
  let html = '';
  let inCodeBlock = false;
  let codeBlockLines = [];
  let codeBlockLang = '';
  let inList = false;
  let listType = '';

  const flushList = () => {
    if (inList) {
      html += `</${listType}>`;
      inList = false;
      listType = '';
    }
  };

  const isCommand = (s) => /^(kubectl|docker|helm|k9s|kubeadm|minikube|kind)\b/.test(s.trim());

  const renderInline = (line) => {
    line = line.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
    line = line.replace(/(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)/g, '<em>$1</em>');
    line = line.replace(/`([^`]+)`/g, (_, code) => {
      // Code is pre-escaped from raw, unescape it to check/process properly
      const unescapedCode = code.replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#039;/g, "'");
      const trimmed = unescapedCode.trim();
      if (isCommand(trimmed)) {
        return `<CMD_PLACEHOLDER data="${trimmed.replace(/"/g, '&quot;')}">`;
      }
      return `<code class="inline-code">${code}</code>`;
    });
    return line;
  };

  const expandPlaceholders = (s) =>
    s.replace(/<CMD_PLACEHOLDER data="([^"]*)">/g, (_, code) => makeCmdBlock(code.replace(/&quot;/g, '"').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&#039;/g, "'"), 'bash'));

  for (let i = 0; i < lines.length; i++) {
    let raw = lines[i];

    const fenceMatch = raw.match(/^```(\w*)\s*$/);
    if (fenceMatch && !inCodeBlock) {
      flushList();
      inCodeBlock = true;
      codeBlockLang = fenceMatch[1] || 'bash';
      codeBlockLines = [];
      continue;
    }
    if (raw.trim() === '```' && inCodeBlock) {
      const code = codeBlockLines.join('\n');
      const safeCode = escapeHtml(code);
      const safeAttr = code.replace(/&/g, '&amp;').replace(/"/g, '&quot;');
      html += `<div class="code-block-wrapper">
        <div class="code-block-header">
          <span class="code-block-lang">${escapeHtml(codeBlockLang)}</span>
          <button class="copy-btn" data-copy-code="${safeAttr}" title="Copy code">
            ${COPY_ICON}<span class="copy-label">Copy code</span>
          </button>
        </div>
        <pre class="code-block"><code>${safeCode}</code></pre>
      </div>`;
      inCodeBlock = false;
      codeBlockLines = [];
      codeBlockLang = '';
      continue;
    }
    if (inCodeBlock) { codeBlockLines.push(raw); continue; }

    raw = escapeHtml(raw);

    if (raw.trim() === '') {
      flushList();
      html += '<div style="height:0.4rem"></div>';
      continue;
    }

    const h3 = raw.match(/^### (.+)/);
    const h2 = raw.match(/^## (.+)/);
    const h1 = raw.match(/^# (.+)/);
    if (h3) { flushList(); html += `<h4 class="md-h4">${renderInline(h3[1])}</h4>`; continue; }
    if (h2) { flushList(); html += `<h3 class="md-h3">${renderInline(h2[1])}</h3>`; continue; }
    if (h1) { flushList(); html += `<h2 class="md-h2">${renderInline(h1[1])}</h2>`; continue; }

    const olMatch = raw.match(/^(\d+)\.\s+(.+)/);
    if (olMatch) {
      if (!inList || listType !== 'ol') { flushList(); html += '<ol class="md-ol">'; inList = true; listType = 'ol'; }
      html += `<li>${expandPlaceholders(renderInline(olMatch[2]))}</li>`;
      continue;
    }

    const ulMatch = raw.match(/^[-*]\s+(.+)/);
    if (ulMatch) {
      if (!inList || listType !== 'ul') { flushList(); html += '<ul class="md-ul">'; inList = true; listType = 'ul'; }
      html += `<li>${expandPlaceholders(renderInline(ulMatch[1]))}</li>`;
      continue;
    }

    flushList();
    html += `<p class="md-p">${expandPlaceholders(renderInline(raw))}</p>`;
  }

  if (inCodeBlock && codeBlockLines.length > 0) {
    html += makeCmdBlock(codeBlockLines.join('\n'), codeBlockLang);
  }
  flushList();
  return expandPlaceholders(html);
}

function attachCopyListeners(container) {
  container.querySelectorAll('[data-copy-code]').forEach(btn => {
    btn.addEventListener('click', () => {
      const code = btn.getAttribute('data-copy-code');
      navigator.clipboard.writeText(code).then(() => {
        const label = btn.querySelector('.copy-label');
        if (label) {
          const orig = label.textContent;
          label.textContent = 'Copied!';
          setTimeout(() => { label.textContent = orig; }, 1800);
        }
      }).catch(() => {
        const ta = document.createElement('textarea');
        ta.value = code;
        document.body.appendChild(ta);
        ta.select();
        document.execCommand('copy');
        document.body.removeChild(ta);
      });
    });
  });
}

// ----- submit handler
async function handleSubmit(ev) {
  ev.preventDefault();

  const message = promptInput.value.trim();
  const filesSnapshot = state.attachedFiles.slice();
  const hasAttachments = filesSnapshot.length > 0;

  if (!message && !hasAttachments) return;

  hideWelcomeHeader();
  autoCollapseSidebar();

  // Render user bubble + attachments chips (do not inline content)
  addUserMessageWithAttachments(message || '', filesSnapshot, false);

  promptInput.value = '';
  promptInput.style.height = 'auto';

  // send snapshot, then clear draft
  const payloadAttachments = filesSnapshot;
  clearDraftAttachments();

  showTypingIndicator();

  try {
    const payload = { message, attachments: payloadAttachments };
    const userId = localStorage.getItem('userId');
    if (userId) payload.userId = userId;
    payload.conversationId = getOrCreateConversationId();

    const { resp, data } = await postChat(payload);

    removeTypingIndicator();
    if (resp.ok) {
      if (data?.conversationId) localStorage.setItem(state.conversationIdKey, String(data.conversationId));
      addMessage(data?.response || "I couldn't process that request.", 'assistant');
      loadChatHistoryIntoTab();
    } else {
      const msg = data?.message || data?.error || `Request failed (${resp.status})`;
      addMessage(`Error: ${msg}`, 'assistant');
    }
  } catch (e) {
    removeTypingIndicator();
    addMessage('Error: Could not connect to the server. Make sure the backend is running.', 'assistant');
  }
}

//initialeaza chatul, adauga event listeners la input si submit
//da bind la attachment clicks din messageArea
export function initChat() {
  promptForm = document.getElementById('prompt-form');
  promptInput = document.getElementById('prompt-input');
  messagesArea = document.getElementById('messages');

  promptInput?.addEventListener('input', () => {
    promptInput.style.height = 'auto';
    promptInput.style.height = Math.min(promptInput.scrollHeight, 150) + 'px';
  });

  promptForm?.addEventListener('submit', handleSubmit);

  promptInput?.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      promptForm.dispatchEvent(new Event('submit'));
    }
  });

  bindChatAttachmentClicks(messagesArea);
}


export function submitFeedback(score, clickedBtn, otherBtn) {
  if (clickedBtn.classList.contains('active')) return;

  const currentConvId = localStorage.getItem(state.conversationIdKey);
  if (!currentConvId) return;

  clickedBtn.classList.add('active');
  if (otherBtn) otherBtn.classList.remove('active');

  fetch(`/api/chat/conversation/${currentConvId}/feedback`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ score })
  })
  .then(res => {
      if (!res.ok) throw new Error('Failed to submit feedback');
  })
  .catch(err => {
      console.error('Feedback error:', err);
      // Revert UI on failure
      clickedBtn.classList.remove('active');
  });
}





