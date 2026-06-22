import { state } from './state.js';
import { escapeHtml, getOrCreateConversationId } from './utils.js';
import { postChat } from './api.js';
import { hideWelcomeHeader, autoCollapseSidebar } from './navigation.js';
import { renderChatAttachmentsHtml, bindChatAttachmentClicks, clearDraftAttachments } from './attachments.js';
import { loadChatHistoryIntoTab } from './history.js';

// DOM refs (initialized in initChat)
let promptForm, promptInput, messagesArea, sendBtn, sendBtnText, sendBtnIcon;

const MAX_MESSAGE_LENGTH = 16000;

// Send/Stop button icons (the markup mirrors the inline SVGs in index.html).
const SEND_ICON = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><line x1="5" y1="12" x2="19" y2="12"/><polyline points="12 5 19 12 12 19"/></svg>';
const STOP_ICON = '<svg viewBox="0 0 24 24" fill="currentColor" stroke="none"><rect x="6" y="6" width="12" height="12" rx="2"/></svg>';

/**
 * Toggle the composer's primary button between "Send" and "Stop" states and keep
 * state.isStreaming in sync. While streaming the button acts as an abort control
 * (handled in handleSubmit) instead of submitting a new message.
 */
function setStreamingUI(streaming) {
  state.isStreaming = streaming;
  if (!sendBtn) return;
  if (streaming) {
    sendBtn.classList.add('is-stopping');
    sendBtn.title = 'Stop generating';
    if (sendBtnText) sendBtnText.textContent = 'Stop';
    if (sendBtnIcon) sendBtnIcon.innerHTML = STOP_ICON;
  } else {
    sendBtn.classList.remove('is-stopping');
    sendBtn.title = 'Send (Enter)';
    if (sendBtnText) sendBtnText.textContent = 'Send';
    if (sendBtnIcon) sendBtnIcon.innerHTML = SEND_ICON;
  }
}

// Set to false to fall back to the non-streaming POST /api/chat endpoint.
const STREAMING_ENABLED = true;

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
// respectStick: when true, only auto-scroll if the user is still pinned to the
// bottom (used when finalizing a streamed answer so we don't yank the view down
// if the user scrolled up to read earlier text).
export function addMessage(text, sender, score = 0, { respectStick = false } = {}) {
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
    if (respectStick && !state.stickToBottom) return;
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

// ----- Streaming helpers

/**
 * Parse a single SSE block (the text between two blank lines) into
 * { event, data }.  The SSE spec allows multiple "data:" lines; we
 * join them with '\n' for multi-line payloads.
 *
 * NOTE: Spring's ServerSentEventHttpMessageWriter emits "data:<value>"
 * WITHOUT the optional separator space after the colon. LLM tokens
 * frequently start with a leading space (e.g. " with", " a", " reason"),
 * so unconditionally stripping a single leading space — as the W3C
 * EventSource spec suggests — would eat those token-leading spaces and
 * concatenate the words ("withareasonof"). We therefore preserve the
 * raw payload verbatim and only strip an optional separator space on
 * "event:" lines, where event names never legitimately start with
 * whitespace.
 */
function parseSseEvent(part) {
  let event = 'message';
  const dataLines = [];
  for (const rawLine of part.split('\n')) {
    // Defensive: tolerate CRLF line endings from intermediate proxies.
    const line = rawLine.endsWith('\r') ? rawLine.slice(0, -1) : rawLine;
    if (line.startsWith('event:')) {
      event = line.slice(6).replace(/^ /, '');
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice(5));
    }
  }
  return { event, data: dataLines.join('\n') };
}

/**
 * Show (or replace) a transient status message inside the chat area while a
 * NEEDS_SEARCH dynamic-search is running.  The element gets the id
 * "status-indicator" so later calls replace rather than stack it.
 *
 * The bubble is removed automatically when the first TOKEN chunk arrives
 * (handled inside {@link sendMessageStreaming}).
 *
 * @param {string} code  stable English identifier (e.g. "searching", "search_done")
 * @param {string} label human-readable Romanian label shown in the UI
 */
function addStatusMessage(code, label) {
  // Replace any existing status indicator instead of appending a second one.
  const existing = document.getElementById('status-indicator');
  if (existing) existing.remove();

  const statusDiv = document.createElement('div');
  statusDiv.className = 'system-status-message';
  statusDiv.id = 'status-indicator';
  statusDiv.dataset.code = code;
  statusDiv.textContent = label;

  // Place the indicator just before the streaming bubble so it visually
  // precedes the response text that is about to arrive.
  const streamingBubble = document.getElementById('streaming-bubble');
  if (streamingBubble) {
    messagesArea.insertBefore(statusDiv, streamingBubble);
  } else {
    messagesArea.appendChild(statusDiv);
  }

  if (state.stickToBottom) messagesArea.scrollTop = messagesArea.scrollHeight;
}

/**
 * Create a temporary "streaming" assistant bubble and return its content div.
 * The bubble gets the id "streaming-bubble" so it can be found and removed later.
 */
function createStreamingBubble() {
  const messageDiv = document.createElement('div');
  messageDiv.className = 'message assistant streaming';
  messageDiv.id = 'streaming-bubble';

  const avatarDiv = document.createElement('div');
  avatarDiv.className = 'message-avatar';
  avatarDiv.textContent = 'AI';

  const contentDiv = document.createElement('div');
  contentDiv.className = 'message-content';

  messageDiv.appendChild(avatarDiv);
  messageDiv.appendChild(contentDiv);
  messagesArea.appendChild(messageDiv);

  setTimeout(() => {
    if (!state.stickToBottom) return;
    messagesArea.scrollTop = messagesArea.scrollHeight;
    messageDiv.scrollIntoView({ behavior: 'smooth', block: 'end' });
  }, 50);

  return contentDiv;
}

/**
 * POST to /api/chat/stream, consume SSE, progressively render tokens.
 * Returns the final conversationId assigned by the server.
 */
async function sendMessageStreaming(text, attachments, conversationId) {
  const payload = { message: text, attachments, conversationId };
  const userId = localStorage.getItem('userId');
  if (userId) payload.userId = userId;

  // One AbortController per send. Aborting it cancels the fetch, which closes the
  // HTTP response and propagates cancellation through the (fully reactive) backend
  // chain up to the LLM, stopping generation. handleSubmit calls abort() when the
  // send button is pressed while streaming.
  const controller = new AbortController();
  state.activeStreamController = controller;

  const response = await fetch('/api/chat/stream', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
    signal: controller.signal,
  });

  if (!response.ok || !response.body) {
    throw new Error(`Stream request failed (${response.status})`);
  }

  const contentDiv = createStreamingBubble();
  const reader = response.body.getReader();
  const decoder = new TextDecoder();

  let rawBuffer = '';   // incomplete SSE frame accumulator
  let fullText = '';    // accumulates all chunk data for final render
  let finalConvId = conversationId;
  let streamDone = false;
  let aborted = false;  // user pressed Stop mid-stream

  try {
    while (!streamDone) {
      const { done, value } = await reader.read();
      if (done) break;

      rawBuffer += decoder.decode(value, { stream: true });

      // SSE events are separated by a blank line (\n\n)
      const parts = rawBuffer.split('\n\n');
      rawBuffer = parts.pop(); // keep the last, potentially incomplete block

      for (const part of parts) {
        if (!part.trim()) continue;
        const { event, data } = parseSseEvent(part);

        if (event === 'meta') {
          try {
            const meta = JSON.parse(data);
            if (meta.conversationId) {
              finalConvId = meta.conversationId;
              localStorage.setItem(state.conversationIdKey, String(finalConvId));
            }
          } catch (_) { /* ignore malformed meta */ }

        } else if (event === 'status') {
          // Backend emitted a progress notification (NEEDS_SEARCH path).
          try {
            const statusData = JSON.parse(data);
            const code  = typeof statusData.code  === 'string' ? statusData.code  : 'info';
            const label = typeof statusData.label === 'string' ? statusData.label : data;
            addStatusMessage(code, label);
          } catch (_) {
            addStatusMessage('info', data);
          }

        } else if (event === 'chunk') {
          // Remove the status indicator on the first real token — the response
          // is now streaming and the progress bubble is no longer needed.
          const statusIndicator = document.getElementById('status-indicator');
          if (statusIndicator) statusIndicator.remove();

          // Chunks are JSON-wrapped ({"text":"..."}) so token-leading whitespace
          // ("of", " the", …) survives the SSE round-trip (the W3C spec strips
          // a single leading space from raw data: payloads, which otherwise eats
          // every space between streamed tokens).
          let piece = '';
          try {
            const parsed = JSON.parse(data);
            piece = typeof parsed === 'object' && parsed !== null && typeof parsed.text === 'string'
              ? parsed.text
              : String(data);
          } catch (_) {
            // Tolerate legacy/raw chunks for backward compatibility
            piece = data;
          }
          fullText += piece;
          // Progressive markdown rendering — kubectl/docker/helm command boxes,
          // code fences, lists and inline formatting appear in real time as the
          // stream progresses, instead of waiting for the final replacement bubble.
          contentDiv.innerHTML = renderMarkdown(fullText);
          attachCopyListeners(contentDiv);
          // Stick-to-bottom: only auto-follow while the user is parked at the
          // bottom, so scrolling up to read earlier text isn't fought per token.
          if (state.stickToBottom) messagesArea.scrollTop = messagesArea.scrollHeight;

        } else if (event === 'done') {
          streamDone = true;
          break;

        } else if (event === 'error') {
          fullText = `Error: ${data}`;
          streamDone = true;
          break;
        }
      }
    }
  } catch (err) {
    // Stop pressed: the fetch was aborted, so reader.read() rejects with
    // AbortError. Treat it as a graceful stop (keep partial text) rather than a
    // failure — genuine errors are re-thrown for handleSubmit to surface.
    if (err && err.name === 'AbortError') {
      aborted = true;
    } else {
      throw err;
    }
  } finally {
    reader.cancel().catch(() => {});
  }

  // Clean up any lingering status indicator (e.g. if stream ended before first token).
  const statusIndicator = document.getElementById('status-indicator');
  if (statusIndicator) statusIndicator.remove();

  // Replace the streaming bubble with a properly formatted message bubble
  // (includes markdown rendering + feedback buttons).
  const streamingBubble = document.getElementById('streaming-bubble');
  if (streamingBubble) streamingBubble.remove();

  // On abort keep whatever was already rendered and append a subtle marker.
  let finalText = fullText;
  if (aborted) {
    finalText = fullText ? `${fullText}\n\n*⏹ stopped*` : '*⏹ stopped*';
  }

  addMessage(finalText || "I couldn't process that request.", 'assistant', 0, { respectStick: true });
  loadChatHistoryIntoTab();

  return finalConvId;
}

// ----- submit handler
async function handleSubmit(ev) {
  ev.preventDefault();

  // While a response is streaming the send button acts as a Stop button.
  if (state.isStreaming) {
    state.activeStreamController?.abort();
    return;
  }

  const message = promptInput.value.trim();
  const filesSnapshot = state.attachedFiles.slice();
  const hasAttachments = filesSnapshot.length > 0;

  if (!message && !hasAttachments) return;

  // Enforce message length limit (mirrors KdiagModels.Message and backend Chat.userMessage)
  if (message.length > MAX_MESSAGE_LENGTH) {
    addMessage(`Error: Message exceeds maximum length of ${MAX_MESSAGE_LENGTH} characters. Current: ${message.length}`, 'assistant');
    return;
  }

  hideWelcomeHeader();
  autoCollapseSidebar();

  // A new send always re-pins to the bottom so the fresh answer auto-follows.
  state.stickToBottom = true;

  // Render user bubble immediately
  addUserMessageWithAttachments(message || '', filesSnapshot, false);

  promptInput.value = '';
  promptInput.style.height = 'auto';

  const payloadAttachments = filesSnapshot;
  clearDraftAttachments();

  const conversationId = getOrCreateConversationId();

  if (STREAMING_ENABLED) {
    // ── Streaming path ──
    setStreamingUI(true);
    try {
      await sendMessageStreaming(message, payloadAttachments, conversationId);
    } catch (e) {
      // Remove any half-created streaming bubble and show an error message.
      // (A user-pressed Stop is handled inside sendMessageStreaming and never
      // throws here, so this only fires on genuine stream failures.)
      const bubble = document.getElementById('streaming-bubble');
      if (bubble) bubble.remove();
      addMessage('Error: Could not stream response from server. Make sure the backend is running.', 'assistant');
    } finally {
      setStreamingUI(false);
      state.activeStreamController = null;
    }
  } else {
    // ── Non-streaming (legacy) path ──
    showTypingIndicator();
    try {
      const payload = { message, attachments: payloadAttachments };
      const userId = localStorage.getItem('userId');
      if (userId) payload.userId = userId;
      payload.conversationId = conversationId;

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
}

//initialeaza chatul, adauga event listeners la input si submit
//da bind la attachment clicks din messageArea
export function initChat() {
  promptForm = document.getElementById('prompt-form');
  promptInput = document.getElementById('prompt-input');
  messagesArea = document.getElementById('messages');
  sendBtn = promptForm?.querySelector('.composer-send');
  sendBtnText = sendBtn?.querySelector('.text');
  sendBtnIcon = sendBtn?.querySelector('.icon');

  promptInput?.addEventListener('input', () => {
    promptInput.style.height = 'auto';
    promptInput.style.height = Math.min(promptInput.scrollHeight, 150) + 'px';
  });

  promptForm?.addEventListener('submit', handleSubmit);

  promptInput?.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      // Don't let Enter start a second stream while one is already running.
      if (state.isStreaming) return;
      promptForm.dispatchEvent(new Event('submit'));
    }
  });

  // Stick-to-bottom tracking: if the user scrolls away from the bottom, pause
  // auto-follow; when they return to within ~80px of the bottom, resume it.
  messagesArea?.addEventListener('scroll', () => {
    const el = messagesArea;
    state.stickToBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
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





