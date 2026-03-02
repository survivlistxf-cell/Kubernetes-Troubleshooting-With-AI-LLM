// Determine Backend API URL - Use proxy through Node.js server
// Node.js server proxies /api/* requests to the backend
const API_URL = '/api';

console.log('🔍 Using API URL:', API_URL);
console.log('� API requests will be proxied through Node.js server');

// Sidebar Toggle
const sidebar = document.getElementById('sidebar');
const toggleBtn = document.getElementById('toggle-btn');
let isFirstMessage = true;

function startNewConversation({ switchToHome = true } = {}) {
    // Generate and store a new conversation id.
    const id = generateConversationId();
    localStorage.setItem('conversationId', id);

    // Clear current chat UI.
    if (typeof messagesArea !== 'undefined' && messagesArea) {
        messagesArea.innerHTML = '';
    }

    // Reset sidebar auto-collapse behavior for a fresh chat.
    isFirstMessage = true;

    // Show welcome header again (nice for a new chat).
    const welcomeHeader = document.querySelector('.welcome-header');
    if (welcomeHeader) {
        welcomeHeader.style.display = '';
    }

    if (switchToHome) {
        switchToTab('home');
    }
}

// ==================== TITLE EDIT MODAL ====================

let titleModalState = { conversationId: null, userId: null };

function openTitleModal({ conversationId, currentTitle, userId }) {
    // Ensure we never stack action modals.
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

function closeTitleModal() {
    const modal = document.getElementById('title-modal');
    const overlay = document.getElementById('title-modal-overlay');
    if (modal) modal.style.display = 'none';
    if (overlay) overlay.style.display = 'none';
    titleModalState = { conversationId: null, userId: null };
}

// ==================== DELETE CONFIRM MODAL ====================

let deleteModalState = { conversationId: null };

function openDeleteModal({ conversationId }) {
    // Ensure we never stack action modals.
    closeTitleModal();
    const modal = document.getElementById('delete-modal');
    const overlay = document.getElementById('delete-modal-overlay');
    if (!modal || !overlay) return;
    deleteModalState = { conversationId };
    modal.style.display = 'flex';
    overlay.style.display = 'block';
}

function closeDeleteModal() {
    const modal = document.getElementById('delete-modal');
    const overlay = document.getElementById('delete-modal-overlay');
    if (modal) modal.style.display = 'none';
    if (overlay) overlay.style.display = 'none';
    deleteModalState = { conversationId: null };
}

// Global keyboard handling for action modals
document.addEventListener('keydown', (ev) => {
    if (ev.key !== 'Escape') return;
    const titleOpen = document.getElementById('title-modal')?.style?.display !== 'none' &&
        document.getElementById('title-modal')?.style?.display;
    const deleteOpen = document.getElementById('delete-modal')?.style?.display !== 'none' &&
        document.getElementById('delete-modal')?.style?.display;

    if (titleOpen) closeTitleModal();
    if (deleteOpen) closeDeleteModal();
});

async function confirmDeleteModal() {
    const convId = deleteModalState.conversationId;
    if (!convId) {
        closeDeleteModal();
        return;
    }

    try {
        const delResp = await fetch(`${API_URL}/chat/conversation/${encodeURIComponent(convId)}`, { method: 'DELETE' });
        const delData = await parseJsonSafely(delResp);
        if (!delResp.ok) {
            alert('Failed to delete conversation: ' + (delData?.error || delData?.message || delResp.statusText));
            return;
        }

        // If we deleted the currently open conversation, start a new one.
        const currentConv = localStorage.getItem('conversationId');
        if (currentConv && String(currentConv) === String(convId)) {
            startNewConversation({ switchToHome: false });
        }

        closeDeleteModal();
        loadChatHistoryIntoTab();
    } catch (e) {
        console.error('Delete failed', e);
        alert('Failed to delete conversation: ' + (e?.message || e));
    }
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
        const resp = await fetch(`${API_URL}/chat/conversation/${encodeURIComponent(conversationId)}/title?userId=${encodeURIComponent(userId)}`, {
            method: 'PATCH',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ title: trimmed })
        });
        const data = await parseJsonSafely(resp);
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

document.addEventListener('DOMContentLoaded', () => {
    const newChatBtn = document.getElementById('new-chat-btn');
    newChatBtn?.addEventListener('click', (ev) => {
        // Ensure we always start a fresh conversation, then land on Home.
        ev.preventDefault();
        startNewConversation({ switchToHome: true });
    });

    // Hook up title modal buttons
    document.getElementById('title-modal-close')?.addEventListener('click', closeTitleModal);
    document.getElementById('title-modal-cancel')?.addEventListener('click', closeTitleModal);
    document.getElementById('title-modal-form')?.addEventListener('submit', (ev) => {
        ev.preventDefault();
        submitTitleModal();
    });

    // Hook up delete modal buttons
    document.getElementById('delete-modal-close')?.addEventListener('click', closeDeleteModal);
    document.getElementById('delete-modal-cancel')?.addEventListener('click', closeDeleteModal);
    document.getElementById('delete-modal-confirm')?.addEventListener('click', confirmDeleteModal);
});

toggleBtn.addEventListener('click', () => {
    sidebar.classList.toggle('collapsed');
});

// Auto-collapse sidebar on first message
function autoCollapseSidebar() {
    if (isFirstMessage) {
        sidebar.classList.add('collapsed');
        isFirstMessage = false;
    }
}

// Hide welcome header when first message is sent
function hideWelcomeHeader() {
    const welcomeHeader = document.querySelector('.welcome-header');
    if (welcomeHeader) {
        welcomeHeader.style.display = 'none';
    }
}

// Tab Navigation
document.querySelectorAll('.nav-item').forEach(button => {
    button.addEventListener('click', () => {
        const tabName = button.dataset.tab;
        
        // Remove active class from all buttons and tabs
        document.querySelectorAll('.nav-item').forEach(btn => btn.classList.remove('active'));
        document.querySelectorAll('.tab-content').forEach(tab => tab.classList.remove('active'));
        
        // Add active class to clicked button and corresponding tab
        button.classList.add('active');
        document.getElementById(tabName).classList.add('active');

        if (tabName === 'chat') {
            loadChatHistoryIntoTab();
        }
    });
});

function switchToTab(tabName) {
    document.querySelectorAll('.nav-item').forEach(btn => btn.classList.remove('active'));
    document.querySelectorAll('.tab-content').forEach(tab => tab.classList.remove('active'));

    const btn = document.querySelector(`.nav-item[data-tab="${tabName}"]`);
    const tab = document.getElementById(tabName);
    if (btn) btn.classList.add('active');
    if (tab) tab.classList.add('active');
}

// ==================== CHAT HISTORY TAB ====================

function formatTimestamp(value) {
    if (!value) return '';
    try {
        const d = new Date(value);
        if (Number.isNaN(d.getTime())) return String(value);
        return d.toLocaleString();
    } catch {
        return String(value);
    }
}

function renderChatHistory(container, chats) {
    if (!container) return;

    if (!Array.isArray(chats) || chats.length === 0) {
        container.innerHTML = '<p style="text-align: center; opacity: 0.7; margin-top: 2rem;">No conversations yet</p>';
        return;
    }

    const safe = chats.slice(0, 200);
    container.innerHTML = safe.map(c => {
        const createdAt = formatTimestamp(c.createdAt || c.created_at);
        const userMessage = (c.userMessage ?? c.user_message ?? '').toString();
        const aiResponse = (c.aiResponse ?? c.ai_response ?? '').toString();
        const conversationId = (c.conversationId ?? c.conversation_id ?? '').toString();
        const canResume = Boolean(conversationId);

        return `
            <div class="chat-history-item" data-conversation-id="${escapeHtml(conversationId)}" style="border: 1px solid rgba(255,255,255,0.12); border-radius: 12px; padding: 14px; margin: 10px 0; background: rgba(0,0,0,0.12); cursor: ${canResume ? 'pointer' : 'default'};">
                <div style="opacity: 0.75; font-size: 0.85rem; margin-bottom: 8px;">${createdAt}</div>
                ${canResume ? `<div style="opacity: 0.65; font-size: 0.8rem; margin-bottom: 8px;">Conversation: ${escapeHtml(conversationId.slice(0, 8))}…</div>` : ''}
                <div style="margin-bottom: 10px;"><strong>You:</strong> ${escapeHtml(userMessage)}</div>
                <div><strong>AI:</strong> ${escapeHtml(aiResponse)}</div>
                ${canResume ? `<div style="margin-top: 10px; opacity: 0.7; font-size: 0.85rem;">Click to resume</div>` : ``}
            </div>
        `;
    }).join('');

    // Note: click-to-resume for the history view is implemented in the normalized
    // conversation list (`renderConversationList`) to avoid mixing legacy /history payloads.
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
        const title = String(c.title || c.conversationTitle || deriveTitleFrom(c) || conversationId.slice(0,8) + '...');
        const lastUpdated = formatTimestamp(c.lastUpdated || c.last_updated || c.createdAt || c.created_at);

        return `
            <div class="conversation-item" data-conversation-id="${escapeHtml(conversationId)}" style="display:flex;align-items:center;justify-content:space-between;border:1px solid rgba(255,255,255,0.08);border-radius:10px;padding:10px;margin:8px 0;background:rgba(0,0,0,0.06);cursor:pointer;">
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

    // Resume button / item click
    container.querySelectorAll('.conversation-item').forEach(el => {
        async function resumeConversation(conversationId) {
            if (!conversationId) return;

            localStorage.setItem('conversationId', String(conversationId));
            switchToTab('home');
            hideWelcomeHeader();

            const userId = localStorage.getItem('userId');
            if (!userId) return;
            try {
                const resp = await fetch(`${API_URL}/chat/conversation/${encodeURIComponent(conversationId)}/messages?userId=${encodeURIComponent(userId)}`);
                const data = await parseJsonSafely(resp);
                if (!resp.ok) {
                    console.warn('Resume failed', data);
                    return;
                }

                const thread = (data?.chats || []).slice();
                messagesArea.innerHTML = '';
                thread.forEach(x => {
                    const um = (x.userMessage ?? x.user_message ?? '').toString();
                    const ar = (x.aiResponse ?? x.ai_response ?? '').toString();
                    if (um) addMessage(um, 'user');
                    if (ar) addMessage(ar, 'assistant');
                });
            } catch (e) {
                console.warn('Failed to resume conversation', e);
            }
        }

        // Clicking the card resumes
        el.addEventListener('click', async (ev) => {
            // If click was on option buttons, ignore (their handlers will run)
            const target = ev.target;
            if (target && (target.classList.contains('conversation-options') || target.classList.contains('resume-btn') || target.classList.contains('edit-title'))) {
                return;
            }

            const convId = el.getAttribute('data-conversation-id');
            await resumeConversation(convId);
        });

        // Resume button
        const resumeBtn = el.querySelector('.resume-btn');
        resumeBtn?.addEventListener('click', async (ev) => {
            ev.stopPropagation();
            const convId = el.getAttribute('data-conversation-id');
            await resumeConversation(convId);
        });

        // Options (delete)
        const optBtn = el.querySelector('.conversation-options');
        optBtn?.addEventListener('click', async (ev) => {
            ev.stopPropagation();
            const convId = el.getAttribute('data-conversation-id');
            if (!convId) return;
            openDeleteModal({ conversationId: convId });
        });

        // Edit title
        const editBtn = el.querySelector('.edit-title');
        editBtn?.addEventListener('click', async (ev) => {
            ev.stopPropagation();
            const userId = localStorage.getItem('userId');
            if (!userId) {
                alert('Login required');
                return;
            }
            const convId = el.getAttribute('data-conversation-id');
            if (!convId) return;
            // Title is the first child div inside the left section.
            const current = el.querySelector('div[style*="flex:1"] > div')?.textContent || '';

            openTitleModal({ conversationId: convId, currentTitle: current, userId });
        });

    });
}

function deriveTitleFrom(c) {
    const m = c?.title || c?.conversationTitle || c?.userMessage || c?.user_message || '';
    if (!m) return '';
    const one = String(m).replace(/\r?\n/g, ' ').trim();
    return one.length <= 60 ? one : one.substring(0,57) + '...';
}

function escapeHtml(str) {
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
}

async function loadChatHistoryIntoTab() {
    const container = document.getElementById('chat-history-content');
    if (!container) return;

    const userId = localStorage.getItem('userId');
    if (!userId) {
        container.innerHTML = '<p style="text-align: center; opacity: 0.7; margin-top: 2rem;">Login to see your chat history.</p>';
        return;
    }

    container.innerHTML = '<p style="text-align: center; opacity: 0.7; margin-top: 2rem;">Loading...</p>';

    try {
        // Prefer normalized conversations endpoint
        let resp = await fetch(`${API_URL}/chat/conversations?userId=${encodeURIComponent(userId)}`);
        let data = await parseJsonSafely(resp);

        if (resp.ok && data?.conversations) {
            renderConversationList(container, data.conversations || []);
            return;
        }

        // Fallback to legacy history endpoint
        resp = await fetch(`${API_URL}/chat/history?userId=${encodeURIComponent(userId)}`);
        data = await parseJsonSafely(resp);
        if (!resp.ok) {
            const msg = data?.message || data?.error || 'Failed to load chat history';
            container.innerHTML = `<p style="text-align: center; color: #d32f2f; margin-top: 2rem;">${escapeHtml(msg)}</p>`;
            return;
        }

        if (data?.conversations) {
            renderConversationList(container, data.conversations || []);
        } else {
            renderChatHistory(container, data?.chats || []);
        }
    } catch (e) {
        container.innerHTML = `<p style="text-align: center; color: #d32f2f; margin-top: 2rem;">${escapeHtml(e?.message || 'Failed to load chat history')}</p>`;
    }
}

// Chat Functionality
const promptForm = document.getElementById('prompt-form');
const promptInput = document.getElementById('prompt-input');
const messagesArea = document.getElementById('messages');

// Show typing indicator
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
    messagesArea.scrollTop = messagesArea.scrollHeight;
}

// Remove typing indicator
function removeTypingIndicator() {
    const typingIndicator = document.getElementById('typing-indicator');
    if (typingIndicator) {
        typingIndicator.remove();
    }
}

// Auto-resize textarea
promptInput.addEventListener('input', () => {
    promptInput.style.height = 'auto';
    promptInput.style.height = Math.min(promptInput.scrollHeight, 150) + 'px';
});

// Handle form submission
promptForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    
    const message = promptInput.value.trim();
    const hasAttachments = attachedFiles.length > 0;

    if (!message && !hasAttachments) {
        return;
    }
    
    // Hide welcome header and collapse sidebar on first message
    hideWelcomeHeader();
    autoCollapseSidebar();
    
    // Build message with attachments context
    let fullMessage = message;
    if (hasAttachments) {
        fullMessage += '\n\n[Attached files]\n';
        attachedFiles.forEach(file => {
            fullMessage += `\nFile: ${file.name} (${formatFileSize(file.size)})\n\`\`\`${file.name.split('.').pop()}\n${file.content}\n\`\`\``;
        });
    }
    
    // Add user message to chat
    addMessage(message || `📎 ${attachedFiles.length} file(s) attached`, 'user');
    promptInput.value = '';
    promptInput.style.height = 'auto';
    attachedFiles = [];
    updateAttachmentsPreview();
    
    // Show typing indicator
    showTypingIndicator();
    
    try {
        const payload = { message: fullMessage };
        const userId = localStorage.getItem('userId');
        if (userId) {
            payload.userId = userId;
        }

        payload.conversationId = getOrCreateConversationId();

        const response = await fetch(`${API_URL}/chat`, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify(payload)
        });
        
        if (response.ok) {
            const data = await response.json();
            if (data?.conversationId) {
                localStorage.setItem('conversationId', String(data.conversationId));
            }
            removeTypingIndicator();
            addMessage(data.response || "I couldn't process that request.", 'assistant');
        } else {
            const err = await parseJsonSafely(response);
            removeTypingIndicator();
            const msg = err?.message || err?.error || `Request failed (${response.status})`;
            addMessage(`Error: ${msg}`, 'assistant');
        }
    } catch (error) {
        console.error('Error:', error);
        removeTypingIndicator();
        addMessage("Error: Could not connect to the server. Make sure the backend is running.", 'assistant');
    }
});

// Handle Enter to send, Shift+Enter for newline
promptInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        promptForm.dispatchEvent(new Event('submit'));
    }
});

// Add message to chat
function addMessage(text, sender) {
    const messageDiv = document.createElement('div');
    messageDiv.className = `message ${sender}`;
    
    const avatarDiv = document.createElement('div');
    avatarDiv.className = 'message-avatar';
    avatarDiv.textContent = sender === 'user' ? 'You' : 'AI';
    
    const contentDiv = document.createElement('div');
    contentDiv.className = 'message-content';

    if (sender === 'assistant') {
        contentDiv.innerHTML = renderMarkdown(text);
        attachCopyListeners(contentDiv);
    } else {
        contentDiv.textContent = text;
    }
    
    messageDiv.appendChild(avatarDiv);
    messageDiv.appendChild(contentDiv);
    messagesArea.appendChild(messageDiv);
    messagesArea.scrollTop = messagesArea.scrollHeight;
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

const COPY_ICON = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="9" y="9" width="13" height="13" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/></svg>`;

function makeCmdBlock(code, lang) {
    const safeCode = escapeHtml(code);
    const safeAttr = code.replace(/&/g,'&amp;').replace(/"/g, '&quot;');
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

function renderMarkdown(text) {
    if (!text) return '';

    const lines = text.split('\n');
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
        // Bold **text**
        line = line.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
        // Italic *text* (not inside **)
        line = line.replace(/(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)/g, '<em>$1</em>');
        // Inline code `code` - commands get extracted, others get styled
        line = line.replace(/`([^`]+)`/g, (_, code) => {
            const trimmed = code.trim();
            if (isCommand(trimmed)) {
                // Return a placeholder - will be replaced after list item is closed
                return `<CMD_PLACEHOLDER data="${trimmed.replace(/"/g,'&quot;')}">`;
            }
            return `<code class="inline-code">${escapeHtml(code)}</code>`;
        });
        return line;
    };

    // Post-process: expand CMD_PLACEHOLDER into full cmd blocks
    const expandPlaceholders = (s) => {
        return s.replace(/<CMD_PLACEHOLDER data="([^"]*)">/g, (_, code) => {
            const decoded = code.replace(/&quot;/g, '"');
            return makeCmdBlock(decoded, 'bash');
        });
    };

    for (let i = 0; i < lines.length; i++) {
        const raw = lines[i];

        // Fenced code block
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
            const safeAttr = code.replace(/&/g,'&amp;').replace(/"/g,'&quot;');
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
        if (inCodeBlock) {
            codeBlockLines.push(raw);
            continue;
        }

        // Empty line
        if (raw.trim() === '') {
            flushList();
            html += '<div style="height:0.4rem"></div>';
            continue;
        }

        // Headings
        const h3 = raw.match(/^### (.+)/);
        const h2 = raw.match(/^## (.+)/);
        const h1 = raw.match(/^# (.+)/);
        if (h3) { flushList(); html += `<h4 class="md-h4">${renderInline(h3[1])}</h4>`; continue; }
        if (h2) { flushList(); html += `<h3 class="md-h3">${renderInline(h2[1])}</h3>`; continue; }
        if (h1) { flushList(); html += `<h2 class="md-h2">${renderInline(h1[1])}</h2>`; continue; }

        // Numbered list
        const olMatch = raw.match(/^(\d+)\.\s+(.+)/);
        if (olMatch) {
            if (!inList || listType !== 'ol') {
                flushList();
                html += '<ol class="md-ol">';
                inList = true;
                listType = 'ol';
            }
            html += `<li>${expandPlaceholders(renderInline(olMatch[2]))}</li>`;
            continue;
        }

        // Bullet list
        const ulMatch = raw.match(/^[-*]\s+(.+)/);
        if (ulMatch) {
            if (!inList || listType !== 'ul') {
                flushList();
                html += '<ul class="md-ul">';
                inList = true;
                listType = 'ul';
            }
            html += `<li>${expandPlaceholders(renderInline(ulMatch[1]))}</li>`;
            continue;
        }

        // Normal paragraph
        flushList();
        html += `<p class="md-p">${expandPlaceholders(renderInline(raw))}</p>`;
    }

    if (inCodeBlock && codeBlockLines.length > 0) {
        html += makeCmdBlock(codeBlockLines.join('\n'), codeBlockLang);
    }
    flushList();

    return expandPlaceholders(html);
}

// ==================== PODS SCANNER ====================

// Pods Scanner Functionality
const scanBtn = document.getElementById('scan-btn');
const scanResults = document.getElementById('scan-results');
const scanLoading = document.getElementById('scan-loading');
const podsList = document.getElementById('pods-list');
const podsAddToChatBtn = document.getElementById('pods-add-to-chat-btn');

// Pod Details modal elements
const podDetailsModal = document.getElementById('pod-details-modal');
const podDetailsOverlay = document.getElementById('pod-details-modal-overlay');
const podDetailsCloseBtn = document.getElementById('pod-details-close');
const podDetailsCancelBtn = document.getElementById('pod-details-cancel');
const podDetailsTitle = document.getElementById('pod-details-title');
const podDetailsMeta = document.getElementById('pod-details-meta');
const podDetailsDescribePre = document.getElementById('pod-details-describe');
const podDetailsJsonPre = document.getElementById('pod-details-json');
const podDetailsEventsPre = document.getElementById('pod-details-events');
const podDetailsLogsPre = document.getElementById('pod-details-logs');
const podDetailsAddContextBtn = document.getElementById('pod-details-add-context');

const podTabButtons = Array.from(document.querySelectorAll('#pod-details-modal .pod-tab-btn'));
const podTabPanels = Array.from(document.querySelectorAll('#pod-details-modal .pod-tab-panel'));

let selectedPodForDetails = null;
let selectedPodDetailsPayload = null;

let lastScannedPods = [];

function generateConversationId() {
    // Prefer Web Crypto when available.
    if (typeof crypto !== 'undefined' && crypto.randomUUID) {
        return crypto.randomUUID();
    }
    // Fallback.
    return `conv_${Date.now()}_${Math.random().toString(16).slice(2)}`;
}

function getOrCreateConversationId() {
    const key = 'conversationId';
    let id = localStorage.getItem(key);
    if (!id) {
        id = generateConversationId();
        localStorage.setItem(key, id);
    }
    return id;
}

function buildPodsContextPayload(level, pods) {
    const conversationId = getOrCreateConversationId();
    const userId = localStorage.getItem('userId');

    const nowIso = new Date().toISOString();
    const targets = (pods || []).slice(0, 50).map(p => ({
        kind: 'Pod',
        name: p.name || 'unknown',
        namespace: p.namespace || 'default'
    }));

    // Level 0 (always): structural summary only.
    const resources = (pods || []).map(p => ({
        kind: 'Pod',
        namespace: p.namespace || 'default',
        name: p.name || 'unknown',
        status: p.status || 'N/A',
        node: p.node || 'N/A',
        ready: p.ready || 'N/A',
        restarts: p.restarts ?? 'N/A',
        age: p.age || 'N/A',
        containers: p.containers || 'N/A'
    }));

    const statusCounts = resources.reduce((acc, r) => {
        const key = r.status || 'N/A';
        acc[key] = (acc[key] || 0) + 1;
        return acc;
    }, {});

    const topStatuses = Object.entries(statusCounts)
        .sort((a, b) => b[1] - a[1])
        .map(([status, count]) => ({ status, count }));

    // NOTE: Events/errors/log evidence are not collected by current scan endpoint.
    // We'll send placeholders/metadata for now, and level 1/2 can be filled server-side later.
    const artifacts = [{
        type: 'pods_scan_level0',
        captured_at: nowIso,
        level: 0,
        content: {
            resources,
            status_summary: topStatuses,
            events_last_20: [],
            top_unique_errors: []
        }
    }];

    if (level >= 1) {
        artifacts.push({
            type: 'pods_scan_level1_requested',
            captured_at: nowIso,
            level: 1,
            content: {
                note: 'Client requested level 1 evidence; server-side enrichment not implemented yet.'
            }
        });
    }

    if (level >= 2) {
        artifacts.push({
            type: 'pods_scan_level2_requested',
            captured_at: nowIso,
            level: 2,
            content: {
                note: 'Client requested level 2 dump; server-side enrichment not implemented yet.'
            }
        });
    }

    return {
        protocol_version: 'kdiag/1.0',
        conversation_id: conversationId,
        source: 'frontend/pods-scanner',
        user_id: userId || null,
        message: {
            role: 'user',
            text: 'Pods context added.'
        },
        context: {
            cluster: {
                namespace: null,
                timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || null
            },
            targets
        },
        artifacts,
        preferences: {
            verbosity: 'medium',
            risk_mode: 'safe',
            redact_secrets: true
        }
    };
}

function escapeHtml(value) {
    return String(value ?? '')
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;')
        .replaceAll("'", '&#039;');
}

function prettyJson(value) {
    try {
        if (value == null) return '';
        if (typeof value === 'string') {
            // If server already returned a block of text, keep it.
            const trimmed = value.trim();
            if (trimmed.startsWith('{') || trimmed.startsWith('[')) {
                return JSON.stringify(JSON.parse(trimmed), null, 2);
            }
            return value;
        }
        return JSON.stringify(value, null, 2);
    } catch {
        return String(value);
    }
}

function renderPodDetailsMeta(pod, details) {
    if (!podDetailsMeta) return;
    const items = [
        { k: 'Namespace', v: pod?.namespace || details?.namespace || 'default' },
        { k: 'Name', v: pod?.name || details?.name || 'unknown' },
        { k: 'Status', v: pod?.status || details?.status || 'N/A' },
        { k: 'Node', v: pod?.node || details?.node || 'N/A' },
        { k: 'Ready', v: pod?.ready || 'N/A' },
        { k: 'Restarts', v: pod?.restarts ?? 'N/A' },
        { k: 'Age', v: pod?.age || 'N/A' }
    ];
    podDetailsMeta.innerHTML = items
        .filter(i => i.v !== undefined && i.v !== null && String(i.v).trim() !== '')
        .map(i => `<span class="pod-details-badge"><strong>${escapeHtml(i.k)}:</strong> ${escapeHtml(i.v)}</span>`)
        .join('');
}

function openPodDetailsModal() {
    if (podDetailsModal) podDetailsModal.style.display = 'flex';
    if (podDetailsOverlay) podDetailsOverlay.style.display = 'block';
}

function closePodDetailsModal() {
    if (podDetailsModal) podDetailsModal.style.display = 'none';
    if (podDetailsOverlay) podDetailsOverlay.style.display = 'none';
    selectedPodForDetails = null;
    selectedPodDetailsPayload = null;
    if (podDetailsDescribePre) podDetailsDescribePre.textContent = '';
    if (podDetailsJsonPre) podDetailsJsonPre.textContent = '';
    if (podDetailsEventsPre) podDetailsEventsPre.textContent = '';
    if (podDetailsLogsPre) podDetailsLogsPre.textContent = '';
    if (podDetailsMeta) podDetailsMeta.innerHTML = '';

    // Reset to Describe tab
    setActivePodTab('describe');
}

podDetailsCloseBtn?.addEventListener('click', closePodDetailsModal);
podDetailsCancelBtn?.addEventListener('click', closePodDetailsModal);
podDetailsOverlay?.addEventListener('click', closePodDetailsModal);

// ESC close for details modal (doesn't interfere with title/delete modals)
document.addEventListener('keydown', (ev) => {
    if (ev.key !== 'Escape') return;
    const open = podDetailsModal?.style?.display && podDetailsModal.style.display !== 'none';
    if (open) closePodDetailsModal();
});

function setActivePodTab(tab) {
    const t = String(tab || 'describe');
    podTabButtons.forEach(b => {
        const isActive = b.dataset.tab === t;
        b.classList.toggle('active', isActive);
        b.setAttribute('aria-selected', isActive ? 'true' : 'false');
    });
    podTabPanels.forEach(p => {
        const isActive = p.dataset.panel === t;
        p.classList.toggle('active', isActive);
        p.hidden = !isActive;
    });
}

podTabButtons.forEach(btn => {
    btn.addEventListener('click', () => {
        setActivePodTab(btn.dataset.tab);
    });
});

async function fetchPodDetails({ namespace, name }) {
    const params = new URLSearchParams();
    if (namespace) params.set('namespace', namespace);
    if (name) params.set('name', name);
    const resp = await fetch(`${API_URL}/pod-details?${params.toString()}`, {
        method: 'GET',
        headers: { 'Content-Type': 'application/json' }
    });
    const data = await parseJsonSafely(resp);
    if (!resp.ok) {
        const msg = data?.error || data?.message || resp.statusText || 'Failed to fetch pod details';
        throw new Error(msg);
    }
    return data;
}

function buildSinglePodContextPayload(pod, detailsPayload) {
    const conversationId = getOrCreateConversationId();
    const userId = localStorage.getItem('userId');
    const nowIso = new Date().toISOString();

    const detailsNs = detailsPayload?.namespace
        || detailsPayload?.podJson?.metadata?.namespace
        || detailsPayload?.pod_json?.metadata?.namespace
        || detailsPayload?.podJson?.metadata?.namespace
        || detailsPayload?.metadata?.namespace;

    const detailsName = detailsPayload?.name
        || detailsPayload?.podJson?.metadata?.name
        || detailsPayload?.pod_json?.metadata?.name
        || detailsPayload?.podJson?.metadata?.name
        || detailsPayload?.metadata?.name;

    const ns = (pod?.namespace ?? detailsNs ?? '').toString().trim() || 'default';
    const name = (pod?.name ?? detailsName ?? '').toString().trim() || 'unknown-pod';

    const normalizedDetails = detailsPayload
        ? {
            ...detailsPayload,
            namespace: detailsPayload?.namespace ?? detailsNs ?? ns,
            name: detailsPayload?.name ?? detailsName ?? name
        }
        : null;

    const detailBlock = {
        summary: {
            namespace: ns,
            name,
            status: pod?.status || 'N/A',
            ready: pod?.ready || 'N/A',
            restarts: pod?.restarts ?? 'N/A',
            age: pod?.age || 'N/A',
            node: pod?.node || 'N/A',
            containers: pod?.containers || 'N/A'
        },
        details: normalizedDetails
    };

    return {
        protocol_version: 'kdiag/1.0',
        conversation_id: conversationId,
        source: 'frontend/pods-details-modal',
        user_id: userId || null,
        message: {
            role: 'user',
            text: `Pod context added: ${ns}/${name}`
        },
        context: {
            cluster: {
                namespace: ns,
                timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || null
            },
            targets: [{ kind: 'Pod', name, namespace: ns }]
        },
        artifacts: [{
            type: 'pod_details',
            captured_at: nowIso,
            level: 1,
            content: detailBlock
        }],
        preferences: {
            verbosity: 'high',
            risk_mode: 'safe',
            redact_secrets: true
        }
    };
}

async function sendPodsContextToServer(payload) {
    const path = `${API_URL}/context`;
    const response = await fetch(path, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json'
        },
        body: JSON.stringify(payload)
    });

    const data = await parseJsonSafely(response);
    if (!response.ok) {
        const msg = data?.message || data?.error || response.statusText || 'Failed to send pods context';
        throw new Error(`${msg} (HTTP ${response.status}) @ ${path}`);
    }

    return data;
}

scanBtn.addEventListener('click', async () => {
    const namespaceInput = document.getElementById('namespace-input');
    const namespace = (namespaceInput?.value || 'default').trim() || 'default';

    scanBtn.disabled = true;
    scanBtn.style.opacity = '0.6';
    scanLoading.style.display = 'block';
    scanResults.style.display = 'none';
    podsList.innerHTML = '';
    lastScannedPods = [];
    if (podsAddToChatBtn) {
        podsAddToChatBtn.style.display = 'none';
        podsAddToChatBtn.disabled = false;
        podsAddToChatBtn.style.opacity = '1';
    }

    try {
        const response = await fetch(`${API_URL}/scan-pods?namespace=${encodeURIComponent(namespace)}`, {
            method: 'GET',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const parsedResponse = await parseJsonSafely(response);
        scanLoading.style.display = 'none';
        scanResults.style.display = 'block';

        if (response.ok) {
            const data = parsedResponse || {};
            if (data.pods && data.pods.length > 0) {
                lastScannedPods = data.pods;
                if (podsAddToChatBtn) {
                    podsAddToChatBtn.style.display = 'inline-flex';
                }
                data.pods.forEach(pod => {
                    const podDiv = document.createElement('div');
                    podDiv.className = 'pod-item';
                    podDiv.innerHTML = `
                        <h4>📦 ${pod.name}</h4>
                        <div class="pod-info">
                            <div class="pod-info-item">
                                <span class="pod-info-label">Namespace:</span>
                                <span>${pod.namespace}</span>
                            </div>
                            <div class="pod-info-item">
                                <span class="pod-info-label">Status:</span>
                                <span>${pod.status}</span>
                            </div>
                            <div class="pod-info-item">
                                <span class="pod-info-label">Node:</span>
                                <span>${pod.node || 'N/A'}</span>
                            </div>
                            <div class="pod-info-item">
                                <span class="pod-info-label">Containers:</span>
                                <span>${pod.containers}</span>
                            </div>
                            ${pod.restarts ? `<div class="pod-info-item">
                                <span class="pod-info-label">Restarts:</span>
                                <span>${pod.restarts}</span>
                            </div>` : ''}
                            ${pod.age ? `<div class="pod-info-item">
                                <span class="pod-info-label">Age:</span>
                                <span>${pod.age}</span>
                            </div>` : ''}
                        </div>
                        <button class="btn-details" type="button" data-pod-namespace="${escapeHtml(pod.namespace)}" data-pod-name="${escapeHtml(pod.name)}">🔎 Details</button>
                    `;
                    podsList.appendChild(podDiv);
                });

                // Delegate Details button clicks
                podsList.querySelectorAll('button.btn-details').forEach(btn => {
                    btn.addEventListener('click', async (ev) => {
                        const ns = ev.currentTarget?.dataset?.podNamespace;
                        const name = ev.currentTarget?.dataset?.podName;
                        const pod = (lastScannedPods || []).find(p => String(p.name) === String(name) && String(p.namespace) === String(ns))
                            || { namespace: ns, name };

                        // Keep a clean identity snapshot for later (e.g., Add Context button).
                        selectedPodForDetails = {
                            namespace: pod?.namespace ?? ns,
                            name: pod?.name ?? name,
                            status: pod?.status,
                            node: pod?.node,
                            ready: pod?.ready,
                            restarts: pod?.restarts,
                            age: pod?.age,
                            containers: pod?.containers
                        };
                        selectedPodDetailsPayload = null;

                        if (podDetailsTitle) {
                            podDetailsTitle.textContent = `Pod details: ${ns}/${name}`;
                        }

                        renderPodDetailsMeta(pod, null);
                        setActivePodTab('describe');
                        if (podDetailsDescribePre) podDetailsDescribePre.textContent = 'Loading details...';
                        if (podDetailsJsonPre) podDetailsJsonPre.textContent = '';
                        if (podDetailsEventsPre) podDetailsEventsPre.textContent = '';
                        if (podDetailsLogsPre) podDetailsLogsPre.textContent = '';

                        openPodDetailsModal();

                        try {
                            const details = await fetchPodDetails({ namespace: ns, name });
                            selectedPodDetailsPayload = details;
                            renderPodDetailsMeta(pod, details);
                            const describe = details?.describe || '(no describe output)';
                            const podJson = details?.podJson || details?.pod_json || null;
                            const events = details?.events || '(no events output)';
                            const logs = details?.logs || '(no logs output)';

                            if (podDetailsDescribePre) {
                                podDetailsDescribePre.textContent = `# kubectl describe pod ${name} -n ${ns}\n${describe}`;
                            }
                            if (podDetailsJsonPre) {
                                podDetailsJsonPre.textContent = `# kubectl get pod ${name} -n ${ns} -o json\n${prettyJson(podJson)}`;
                            }
                            if (podDetailsEventsPre) {
                                podDetailsEventsPre.textContent = `# kubectl get events -n ${ns} --field-selector involvedObject.name=${name}\n${events}`;
                            }
                            if (podDetailsLogsPre) {
                                podDetailsLogsPre.textContent = `# kubectl logs ${name} -n ${ns} --tail=200\n${logs}`;
                            }
                        } catch (e) {
                            if (podDetailsDescribePre) {
                                podDetailsDescribePre.textContent = `Failed to load pod details: ${e?.message || e}`;
                            }
                        }
                    });
                });
            } else {
                lastScannedPods = [];
                if (podsAddToChatBtn) {
                    podsAddToChatBtn.style.display = 'inline-flex';
                }
                podsList.innerHTML = '<p style="text-align: center; opacity: 0.7;">No Kubernetes pods found on this system.</p>';
            }
        } else {
            const message = parsedResponse?.error || parsedResponse?.message || 'Could not scan for pods';
            lastScannedPods = [];
            if (podsAddToChatBtn) {
                podsAddToChatBtn.style.display = 'none';
            }
            podsList.innerHTML = `<p style="text-align: center; color: #d32f2f;">Error: ${message}</p>`;
        }
    } catch (error) {
        console.error('Scan error:', error);
        scanLoading.style.display = 'none';
        scanResults.style.display = 'block';
        lastScannedPods = [];
        if (podsAddToChatBtn) {
            podsAddToChatBtn.style.display = 'none';
        }
        podsList.innerHTML = `<p style="text-align: center; color: #d32f2f;">Error: ${error.message}</p>`;
    } finally {
        scanBtn.disabled = false;
        scanBtn.style.opacity = '1';
    }
});

podDetailsAddContextBtn?.addEventListener('click', async () => {
    if (!selectedPodForDetails) return;

    const pod = selectedPodForDetails;
    const details = selectedPodDetailsPayload;
    const ns = pod?.namespace || details?.namespace || 'default';
    const name = pod?.name || details?.name || 'unknown-pod';

    // Build a readable text summary for the attachment
    let contextContent = `=== POD DETAILS: ${ns}/${name} ===\n`;
    contextContent += `Namespace: ${ns}\n`;
    contextContent += `Name: ${name}\n`;
    contextContent += `Status: ${pod?.status || 'N/A'}\n`;
    contextContent += `Node: ${pod?.node || 'N/A'}\n`;
    contextContent += `Ready: ${pod?.ready || 'N/A'}\n`;
    contextContent += `Restarts: ${pod?.restarts ?? 'N/A'}\n`;
    contextContent += `Age: ${pod?.age || 'N/A'}\n`;
    contextContent += `Containers: ${pod?.containers || 'N/A'}\n`;

    if (details) {
        if (details.describe) {
            contextContent += `\n--- kubectl describe ---\n${details.describe}\n`;
        }
        const podJson = details?.podJson || details?.pod_json || null;
        if (podJson) {
            contextContent += `\n--- kubectl get pod -o json ---\n${prettyJson(podJson)}\n`;
        }
        if (details.events) {
            contextContent += `\n--- Events ---\n${details.events}\n`;
        }
        if (details.logs) {
            contextContent += `\n--- Logs (tail 200) ---\n${details.logs}\n`;
        }
    }

    attachedFiles.push({
        name: `pod-${name}.txt`,
        size: contextContent.length,
        type: 'text/plain',
        content: contextContent,
        _scanContext: true
    });

    closePodDetailsModal();
    updateAttachmentsPreview();
    switchToTab('home');
});

podsAddToChatBtn?.addEventListener('click', async () => {
    if (!podsAddToChatBtn) return;
    if (!Array.isArray(lastScannedPods) || lastScannedPods.length === 0) return;

    // Build a readable summary as an attached file (same format as attach-scan-option)
    let contextContent = `=== PODS SCAN (${lastScannedPods.length} pods) ===\n`;
    lastScannedPods.forEach(p => {
        contextContent += `\nPod: ${p.name}\n`;
        contextContent += `  Namespace: ${p.namespace || 'default'}\n`;
        contextContent += `  Status: ${p.status || 'N/A'}\n`;
        contextContent += `  Node: ${p.node || 'N/A'}\n`;
        contextContent += `  Ready: ${p.ready || 'N/A'}\n`;
        contextContent += `  Restarts: ${p.restarts ?? 'N/A'}\n`;
        contextContent += `  Age: ${p.age || 'N/A'}\n`;
        contextContent += `  Containers: ${p.containers || 'N/A'}\n`;
    });

    attachedFiles.push({
        name: `k8s-pods-context.txt`,
        size: contextContent.length,
        type: 'text/plain',
        content: contextContent,
        _scanContext: true
    });

    updateAttachmentsPreview();
    switchToTab('home');
});

// Nodes Scanner Functionality
const scanNodesBtn = document.getElementById('scan-nodes-btn');
const nodesScanResults = document.getElementById('nodes-scan-results');
const nodesScanLoading = document.getElementById('nodes-scan-loading');
const nodesList = document.getElementById('nodes-list');

// Node Details modal elements
const nodeDetailsModal = document.getElementById('node-details-modal');
const nodeDetailsOverlay = document.getElementById('node-details-modal-overlay');
const nodeDetailsCloseBtn = document.getElementById('node-details-close');
const nodeDetailsCancelBtn = document.getElementById('node-details-cancel');
const nodeDetailsTitle = document.getElementById('node-details-title');
const nodeDetailsMeta = document.getElementById('node-details-meta');
const nodeDetailsDescribePre = document.getElementById('node-details-describe');
const nodeDetailsJsonPre = document.getElementById('node-details-json');
const nodeDetailsEventsPre = document.getElementById('node-details-events');

const nodeTabButtons = Array.from(document.querySelectorAll('#node-details-modal .pod-tab-btn'));
const nodeTabPanels = Array.from(document.querySelectorAll('#node-details-modal .pod-tab-panel'));

let lastScannedNodes = [];
let selectedNodeForDetails = null;
let selectedNodeDetailsPayload = null;
let selectedNodeDetailsMode = 'fast';
let isLoadingNodeDetails = false;

function renderNodeDetailsMeta(node, details) {
    if (!nodeDetailsMeta) return;
    const items = [
        { k: 'Name', v: node?.name || details?.name || 'unknown' },
        { k: 'Status', v: node?.status || details?.status || 'N/A' },
        { k: 'Roles', v: node?.roles || 'N/A' },
        { k: 'Age', v: node?.age || 'N/A' },
        { k: 'Version', v: node?.version || 'N/A' },
        { k: 'Internal IP', v: node?.internalIp || node?.internal_ip || 'N/A' },
        { k: 'External IP', v: node?.externalIp || node?.external_ip || 'N/A' }
    ];
    nodeDetailsMeta.innerHTML = items
        .filter(i => i.v !== undefined && i.v !== null && String(i.v).trim() !== '')
        .map(i => `<span class="pod-details-badge"><strong>${escapeHtml(i.k)}:</strong> ${escapeHtml(i.v)}</span>`)
        .join('');
}

function openNodeDetailsModal() {
    if (nodeDetailsModal) nodeDetailsModal.style.display = 'flex';
    if (nodeDetailsOverlay) nodeDetailsOverlay.style.display = 'block';
}

function closeNodeDetailsModal() {
    if (nodeDetailsModal) nodeDetailsModal.style.display = 'none';
    if (nodeDetailsOverlay) nodeDetailsOverlay.style.display = 'none';
    selectedNodeForDetails = null;
    selectedNodeDetailsPayload = null;
    selectedNodeDetailsMode = 'fast';
    isLoadingNodeDetails = false;
    if (nodeDetailsDescribePre) nodeDetailsDescribePre.textContent = '';
    if (nodeDetailsJsonPre) nodeDetailsJsonPre.textContent = '';
    if (nodeDetailsEventsPre) nodeDetailsEventsPre.textContent = '';

    setActiveNodeTab('describe');
}

nodeDetailsCloseBtn?.addEventListener('click', closeNodeDetailsModal);
nodeDetailsCancelBtn?.addEventListener('click', closeNodeDetailsModal);
nodeDetailsOverlay?.addEventListener('click', closeNodeDetailsModal);

// ESC close for node details modal (doesn't interfere with title/delete/pod modals)
document.addEventListener('keydown', (ev) => {
    if (ev.key !== 'Escape') return;
    const open = nodeDetailsModal?.style?.display && nodeDetailsModal.style.display !== 'none';
    if (open) closeNodeDetailsModal();
});

function setActiveNodeTab(tab) {
    const t = String(tab || 'describe');
    nodeTabButtons.forEach(b => {
        const isActive = b.dataset.tab === t;
        b.classList.toggle('active', isActive);
        b.setAttribute('aria-selected', isActive ? 'true' : 'false');
    });
    nodeTabPanels.forEach(p => {
        const isActive = p.dataset.panel === t;
        p.classList.toggle('active', isActive);
        p.hidden = !isActive;
    });
}

nodeTabButtons.forEach(btn => {
    btn.addEventListener('click', async () => {
        const tab = btn.dataset.tab;
        setActiveNodeTab(tab);

        // Describe/Events can be slow, so backend defaults to fast mode.
        // When user explicitly opens these tabs, upgrade to full mode once.
        if ((tab === 'describe' || tab === 'events') && selectedNodeForDetails?.name) {
            await ensureNodeDetailsFullModeLoaded(tab);
        }
    });
});

async function fetchNodeDetails({ name, mode } = {}) {
    const params = new URLSearchParams();
    if (name) params.set('name', name);
    if (mode) params.set('mode', mode);
    const resp = await fetch(`${API_URL}/node-details?${params.toString()}`, {
        method: 'GET',
        headers: { 'Content-Type': 'application/json' }
    });
    const data = await parseJsonSafely(resp);
    if (!resp.ok) {
        const msg = data?.error || data?.message || resp.statusText || 'Failed to fetch node details';
        throw new Error(msg);
    }
    return data;
}

function applyNodeDetailsToUi({ name, details }) {
    if (!details) return;

    const describe = details?.describe || '(no describe output)';
    const nodeJson = details?.nodeJson || details?.node_json || null;
    const events = details?.events || '(no events output)';

    if (nodeDetailsDescribePre) {
        nodeDetailsDescribePre.textContent = `# kubectl describe node ${name}\n${describe}`;
    }
    if (nodeDetailsJsonPre) {
        nodeDetailsJsonPre.textContent = `# kubectl get node ${name} -o json\n${prettyJson(nodeJson)}`;
    }
    if (nodeDetailsEventsPre) {
        nodeDetailsEventsPre.textContent = `# kubectl get events --all-namespaces --field-selector involvedObject.kind=Node,involvedObject.name=${name}\n${events}`;
    }
}

async function ensureNodeDetailsFullModeLoaded(activeTab) {
    if (selectedNodeDetailsMode === 'full') return;
    if (isLoadingNodeDetails) return;
    const name = selectedNodeForDetails?.name;
    if (!name) return;

    try {
        isLoadingNodeDetails = true;
        if (activeTab === 'describe' && nodeDetailsDescribePre) nodeDetailsDescribePre.textContent = 'Loading full describe...';
        if (activeTab === 'events' && nodeDetailsEventsPre) nodeDetailsEventsPre.textContent = 'Loading full events...';

        const details = await fetchNodeDetails({ name, mode: 'full' });
        selectedNodeDetailsPayload = details;
        selectedNodeDetailsMode = 'full';
        renderNodeDetailsMeta(selectedNodeForDetails, details);
        applyNodeDetailsToUi({ name, details });
    } catch (e) {
        // Don't spam the user; just show an error in the active panel.
        if (activeTab === 'describe' && nodeDetailsDescribePre) {
            nodeDetailsDescribePre.textContent = `Failed to load full describe: ${e?.message || e}`;
        }
        if (activeTab === 'events' && nodeDetailsEventsPre) {
            nodeDetailsEventsPre.textContent = `Failed to load full events: ${e?.message || e}`;
        }
    } finally {
        isLoadingNodeDetails = false;
    }
}

scanNodesBtn.addEventListener('click', async () => {
    scanNodesBtn.disabled = true;
    scanNodesBtn.style.opacity = '0.6';
    nodesScanLoading.style.display = 'block';
    nodesScanResults.style.display = 'none';
    nodesList.innerHTML = '';
    lastScannedNodes = [];

    try {
        const nodesResponse = await fetch(`${API_URL}/scan-nodes`, {
            method: 'GET',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        const parsedResponse = await parseJsonSafely(nodesResponse);
        nodesScanLoading.style.display = 'none';
        nodesScanResults.style.display = 'block';

        if (nodesResponse.ok) {
            const data = parsedResponse || {};
            if (data.nodes && data.nodes.length > 0) {
                lastScannedNodes = data.nodes;
                data.nodes.forEach(node => {
                    const nodeDiv = document.createElement('div');
                    nodeDiv.className = 'node-item';
                    nodeDiv.innerHTML = `
                        <h4>🖥️ ${node.name}</h4>
                        <div class="node-info">
                            <div class="node-info-item">
                                <span class="node-info-label">Status:</span>
                                <span>${node.status}</span>
                            </div>
                            <div class="node-info-item">
                                <span class="node-info-label">Roles:</span>
                                <span>${node.roles || 'N/A'}</span>
                            </div>
                            <div class="node-info-item">
                                <span class="node-info-label">Age:</span>
                                <span>${node.age || 'N/A'}</span>
                            </div>
                            <div class="node-info-item">
                                <span class="node-info-label">Version:</span>
                                <span>${node.version || 'N/A'}</span>
                            </div>
                            ${node.internalIp ? `<div class="node-info-item">
                                <span class="node-info-label">Internal IP:</span>
                                <span>${node.internalIp}</span>
                            </div>` : ''}
                            ${node.externalIp ? `<div class="node-info-item">
                                <span class="node-info-label">External IP:</span>
                                <span>${node.externalIp}</span>
                            </div>` : ''}
                        </div>
                        <button class="btn-details" type="button" data-node-name="${escapeHtml(node.name)}">🔎 Details</button>
                    `;
                    nodesList.appendChild(nodeDiv);
                });

                // Delegate Details button clicks
                nodesList.querySelectorAll('button.btn-details').forEach(btn => {
                    btn.addEventListener('click', async (ev) => {
                        const name = ev.currentTarget?.dataset?.nodeName;
                        const node = (lastScannedNodes || []).find(n => String(n.name) === String(name)) || { name };

                        selectedNodeForDetails = {
                            name: node?.name ?? name,
                            status: node?.status,
                            roles: node?.roles,
                            age: node?.age,
                            version: node?.version,
                            internalIp: node?.internalIp,
                            externalIp: node?.externalIp
                        };
                        selectedNodeDetailsPayload = null;
                        selectedNodeDetailsMode = 'fast';
                        isLoadingNodeDetails = false;

                        if (nodeDetailsTitle) nodeDetailsTitle.textContent = `Node details: ${name}`;

                        renderNodeDetailsMeta(node, null);
                        setActiveNodeTab('describe');
                        if (nodeDetailsDescribePre) nodeDetailsDescribePre.textContent = 'Loading details...';
                        if (nodeDetailsJsonPre) nodeDetailsJsonPre.textContent = '';
                        if (nodeDetailsEventsPre) nodeDetailsEventsPre.textContent = '';

                        openNodeDetailsModal();

                        try {
                            const details = await fetchNodeDetails({ name, mode: 'fast' });
                            selectedNodeDetailsPayload = details;
                            selectedNodeDetailsMode = details?.mode || 'fast';
                            renderNodeDetailsMeta(node, details);

                            applyNodeDetailsToUi({ name, details });
                        } catch (e) {
                            if (nodeDetailsDescribePre) {
                                nodeDetailsDescribePre.textContent = `Failed to load node details: ${e?.message || e}`;
                            }
                        }
                    });
                });
            } else {
                nodesList.innerHTML = '<p style="text-align: center; opacity: 0.7;">No Kubernetes nodes found on this system.</p>';
            }
        } else {
            const message = parsedResponse?.error || parsedResponse?.message || 'Could not scan for nodes';
            nodesList.innerHTML = `<p style="text-align: center; color: #d32f2f;">Error: ${message}</p>`;
        }
    } catch (error) {
        console.error('Nodes scan error:', error);
        nodesScanLoading.style.display = 'none';
        nodesScanResults.style.display = 'block';
        nodesList.innerHTML = `<p style="text-align: center; color: #d32f2f;">Error: ${error.message}</p>`;
    } finally {
        scanNodesBtn.disabled = false;
        scanNodesBtn.style.opacity = '1';
    }
});

// ==================== AUTH SYSTEM ====================

// Check if user is already logged in on page load
document.addEventListener('DOMContentLoaded', () => {
    const token = localStorage.getItem('authToken');
    const user = localStorage.getItem('currentUser');
    
    if (token && user) {
        updateUIAfterLogin(user);
    }
});

// Modal Functions
function openLoginModal() {
    document.getElementById('auth-modal').style.display = 'block';
    document.getElementById('auth-modal-overlay').style.display = 'block';
    document.getElementById('login-form-container').style.display = 'block';
    document.getElementById('register-form-container').style.display = 'none';
}

function closeAuthModal() {
    document.getElementById('auth-modal').style.display = 'none';
    document.getElementById('auth-modal-overlay').style.display = 'none';
    clearAuthMessages();
}

function switchToRegister(e) {
    e.preventDefault();
    document.getElementById('login-form-container').style.display = 'none';
    document.getElementById('register-form-container').style.display = 'block';
    clearAuthMessages();
}

function switchToLogin(e) {
    e.preventDefault();
    document.getElementById('login-form-container').style.display = 'block';
    document.getElementById('register-form-container').style.display = 'none';
    clearAuthMessages();
}

function showAuthMessage(message, type = 'error', containerId = 'login-form-container') {
    const container = document.getElementById(containerId);
    const existingMessage = container.querySelector('.auth-message');
    if (existingMessage) existingMessage.remove();
    
    const messageEl = document.createElement('div');
    messageEl.className = `auth-message ${type}`;
    messageEl.textContent = message;
    container.insertBefore(messageEl, container.querySelector('.auth-form'));
}

function clearAuthMessages() {
    document.querySelectorAll('.auth-message').forEach(el => el.remove());
}

async function parseJsonSafely(response) {
    try {
        return await response.json();
    } catch (error) {
        console.warn('Unable to parse JSON from response:', error);
        return null;
    }
}

// Login Handler
document.getElementById('login-form')?.addEventListener('submit', async (e) => {
    e.preventDefault();
    
    const email = document.getElementById('login-email').value;
    const password = document.getElementById('login-password').value;
    
    try {
        const response = await fetch(`${API_URL}/auth/login`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email, password })
        });
        
        const data = await response.json();
        
        if (response.ok) {
            localStorage.setItem('authToken', data.token);
            localStorage.setItem('currentUser', data.username);
            if (data.userId) {
                localStorage.setItem('userId', data.userId);
            }
            updateUIAfterLogin(data.username);
            closeAuthModal();
            showAuthMessage('Login successful!', 'success', 'home');
        } else {
            showAuthMessage(data.message || 'Login failed', 'error', 'login-form-container');
        }
    } catch (error) {
        console.error('Login error:', error);
        showAuthMessage('Server error: ' + error.message, 'error', 'login-form-container');
    }
});

// Register Handler
document.getElementById('register-form')?.addEventListener('submit', async (e) => {
    e.preventDefault();
    
    const username = document.getElementById('register-username').value;
    const email = document.getElementById('register-email').value;
    const password = document.getElementById('register-password').value;
    const confirmPassword = document.getElementById('register-confirm').value;
    
    if (password !== confirmPassword) {
        showAuthMessage('Passwords do not match', 'error', 'register-form-container');
        return;
    }
    
    try {
        const response = await fetch(`${API_URL}/auth/register`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ username, email, password })
        });
        
        const data = await response.json();
        
        if (response.ok) {
            showAuthMessage('Account created! Please login.', 'success', 'register-form-container');
            setTimeout(() => switchToLogin({ preventDefault: () => {} }), 1500);
        } else {
            showAuthMessage(data.message || 'Registration failed', 'error', 'register-form-container');
        }
    } catch (error) {
        console.error('Register error:', error);
        showAuthMessage('Server error: ' + error.message, 'error', 'register-form-container');
    }
});

// Logout Handler
document.getElementById('logout-btn')?.addEventListener('click', () => {
    localStorage.removeItem('authToken');
    localStorage.removeItem('currentUser');
    localStorage.removeItem('userId');
    location.reload();
});

function updateUIAfterLogin(username) {
    document.getElementById('auth-btn').style.display = 'none';
    document.getElementById('logout-btn').style.display = 'block';
    document.getElementById('user-info').style.display = 'block';
    document.getElementById('user-info').textContent = `👤 ${username}`;
}

// ==================== ATTACHMENT SYSTEM ====================

const attachBtn = document.getElementById('attach-btn');
const attachDropdown = document.getElementById('attach-dropdown');
const attachFileOption = document.getElementById('attach-file-option');
const attachScanOption = document.getElementById('attach-scan-option');
const fileInput = document.getElementById('file-input');
const attachmentsPreview = document.getElementById('attachments-preview');
let attachedFiles = [];

// Toggle dropdown
function toggleAttachDropdown(forceClose) {
    if (!attachDropdown) return;
    const isOpen = attachDropdown.style.display !== 'none';
    if (forceClose || isOpen) {
        attachDropdown.style.display = 'none';
        attachBtn?.classList.remove('open');
    } else {
        // Update scan option availability
        const hasScanData = (Array.isArray(lastScannedPods) && lastScannedPods.length > 0)
            || (Array.isArray(lastScannedNodes) && lastScannedNodes.length > 0);
        if (attachScanOption) {
            attachScanOption.disabled = !hasScanData;
            const desc = attachScanOption.querySelector('.attach-dropdown-desc');
            if (desc) {
                desc.textContent = hasScanData
                    ? `${lastScannedPods?.length || 0} pods, ${lastScannedNodes?.length || 0} nodes available`
                    : 'Scan pods or nodes first';
            }
        }
        attachDropdown.style.display = 'block';
        attachBtn?.classList.add('open');
    }
}

attachBtn.addEventListener('click', (e) => {
    e.stopPropagation();
    toggleAttachDropdown();
});

// Close dropdown on outside click
document.addEventListener('click', (e) => {
    if (!attachDropdown || attachDropdown.style.display === 'none') return;
    const wrapper = e.target.closest('.attach-dropdown-wrapper');
    if (!wrapper) {
        toggleAttachDropdown(true);
    }
});

// Option 1: Attach file from computer
attachFileOption?.addEventListener('click', () => {
    toggleAttachDropdown(true);
    fileInput.click();
});

// Option 2: Attach scanned context (pods + nodes)
attachScanOption?.addEventListener('click', () => {
    toggleAttachDropdown(true);

    const hasPods = Array.isArray(lastScannedPods) && lastScannedPods.length > 0;
    const hasNodes = Array.isArray(lastScannedNodes) && lastScannedNodes.length > 0;

    if (!hasPods && !hasNodes) return;

    // Build a readable summary as an "attached file"
    let contextContent = '';

    if (hasPods) {
        contextContent += `=== PODS SCAN (${lastScannedPods.length} pods) ===\n`;
        lastScannedPods.forEach(p => {
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
        contextContent += `=== NODES SCAN (${lastScannedNodes.length} nodes) ===\n`;
        lastScannedNodes.forEach(n => {
            contextContent += `\nNode: ${n.name}\n`;
            contextContent += `  Status: ${n.status || 'N/A'}\n`;
            contextContent += `  Roles: ${n.roles || 'N/A'}\n`;
            contextContent += `  Age: ${n.age || 'N/A'}\n`;
            contextContent += `  Version: ${n.version || 'N/A'}\n`;
            contextContent += `  Internal IP: ${n.internalIp || 'N/A'}\n`;
            contextContent += `  External IP: ${n.externalIp || 'N/A'}\n`;
        });
    }

    const parts = [];
    if (hasPods) parts.push(`${lastScannedPods.length} pods`);
    if (hasNodes) parts.push(`${lastScannedNodes.length} nodes`);
    const label = `scan-context (${parts.join(', ')})`;

    attachedFiles.push({
        name: `k8s-scan-context.txt`,
        size: contextContent.length,
        type: 'text/plain',
        content: contextContent,
        _scanContext: true
    });

    updateAttachmentsPreview();
});

// ==================== RESOURCE PICKER MODAL ====================

const resourcePickerModal = document.getElementById('resource-picker-modal');
const resourcePickerOverlay = document.getElementById('resource-picker-overlay');
const resourcePickerClose = document.getElementById('resource-picker-close');
const resourcePickerCancel = document.getElementById('resource-picker-cancel');
const resourcePickerAdd = document.getElementById('resource-picker-add');
const resourcePickerTitle = document.getElementById('resource-picker-title');
const resourcePickerList = document.getElementById('resource-picker-list');
const resourcePickerSelectAllCb = document.getElementById('resource-picker-select-all-cb');
const resourcePickerCount = document.getElementById('resource-picker-count');
const attachBrowsePodsOption = document.getElementById('attach-browse-pods-option');
const attachBrowseNodesOption = document.getElementById('attach-browse-nodes-option');

let pickerKind = null;
let pickerItems = [];

function openResourcePicker(kind) {
    pickerKind = kind;
    const isPods = kind === 'pods';
    const data = isPods ? lastScannedPods : lastScannedNodes;

    if (!Array.isArray(data) || data.length === 0) {
        alert(isPods ? 'No pods scanned yet. Go to Pods Scanner first.' : 'No nodes scanned yet. Go to Nodes Scanner first.');
        return;
    }

    pickerItems = data.map((item, i) => ({ ...item, _selected: false, _index: i }));
    if (resourcePickerTitle) resourcePickerTitle.textContent = isPods ? '📦 Select Pods' : '🖥️ Select Nodes';
    if (resourcePickerSelectAllCb) resourcePickerSelectAllCb.checked = false;
    renderPickerList();
    updatePickerCount();

    if (resourcePickerModal) resourcePickerModal.style.display = 'flex';
    if (resourcePickerOverlay) resourcePickerOverlay.style.display = 'block';
}

function closeResourcePicker() {
    if (resourcePickerModal) resourcePickerModal.style.display = 'none';
    if (resourcePickerOverlay) resourcePickerOverlay.style.display = 'none';
    pickerKind = null;
    pickerItems = [];
}

resourcePickerClose?.addEventListener('click', closeResourcePicker);
resourcePickerCancel?.addEventListener('click', closeResourcePicker);
resourcePickerOverlay?.addEventListener('click', closeResourcePicker);

document.addEventListener('keydown', (ev) => {
    if (ev.key !== 'Escape') return;
    if (resourcePickerModal?.style?.display && resourcePickerModal.style.display !== 'none') closeResourcePicker();
});

function updatePickerCount() {
    const count = pickerItems.filter(i => i._selected).length;
    if (resourcePickerCount) resourcePickerCount.textContent = `${count} selected`;
    if (resourcePickerAdd) resourcePickerAdd.disabled = count === 0;
}

function renderPickerList() {
    if (!resourcePickerList) return;
    const isPods = pickerKind === 'pods';

    if (pickerItems.length === 0) {
        resourcePickerList.innerHTML = `<div class="resource-picker-empty">No ${isPods ? 'pods' : 'nodes'} available.</div>`;
        return;
    }

    resourcePickerList.innerHTML = pickerItems.map((item, idx) => {
        const name = escapeHtml(item.name || 'unknown');
        let badges = '';
        if (isPods) {
            badges = `<span class="resource-picker-item-badge">${escapeHtml(item.namespace || 'default')}</span>
                <span class="resource-picker-item-badge">${escapeHtml(item.status || 'N/A')}</span>
                ${item.node ? `<span class="resource-picker-item-badge">${escapeHtml(item.node)}</span>` : ''}`;
        } else {
            badges = `<span class="resource-picker-item-badge">${escapeHtml(item.status || 'N/A')}</span>
                ${item.roles ? `<span class="resource-picker-item-badge">${escapeHtml(item.roles)}</span>` : ''}
                ${item.version ? `<span class="resource-picker-item-badge">${escapeHtml(item.version)}</span>` : ''}`;
        }
        return `<div class="resource-picker-item ${item._selected ? 'selected' : ''}" data-picker-idx="${idx}">
            <input type="checkbox" ${item._selected ? 'checked' : ''} />
            <div class="resource-picker-item-info">
                <div class="resource-picker-item-name">${isPods ? '📦' : '🖥️'} ${name}</div>
                <div class="resource-picker-item-meta">${badges}</div>
            </div>
        </div>`;
    }).join('');

    resourcePickerList.querySelectorAll('.resource-picker-item').forEach(el => {
        el.addEventListener('click', () => {
            const idx = parseInt(el.dataset.pickerIdx);
            if (isNaN(idx) || !pickerItems[idx]) return;
            pickerItems[idx]._selected = !pickerItems[idx]._selected;
            el.classList.toggle('selected', pickerItems[idx]._selected);
            const cb = el.querySelector('input[type="checkbox"]');
            if (cb) cb.checked = pickerItems[idx]._selected;
            if (resourcePickerSelectAllCb) resourcePickerSelectAllCb.checked = pickerItems.every(i => i._selected);
            updatePickerCount();
        });
    });
}

resourcePickerSelectAllCb?.addEventListener('change', () => {
    const checked = resourcePickerSelectAllCb.checked;
    pickerItems.forEach(i => i._selected = checked);
    renderPickerList();
    updatePickerCount();
});

resourcePickerAdd?.addEventListener('click', () => {
    const selected = pickerItems.filter(i => i._selected);
    if (selected.length === 0) return;
    const isPods = pickerKind === 'pods';
    let contextContent = '';

    if (isPods) {
        contextContent += `=== SELECTED PODS (${selected.length}) ===\n`;
        selected.forEach(p => {
            contextContent += `\nPod: ${p.name}\n  Namespace: ${p.namespace || 'default'}\n  Status: ${p.status || 'N/A'}\n  Node: ${p.node || 'N/A'}\n  Ready: ${p.ready || 'N/A'}\n  Restarts: ${p.restarts ?? 'N/A'}\n  Age: ${p.age || 'N/A'}\n  Containers: ${p.containers || 'N/A'}\n`;
        });
    } else {
        contextContent += `=== SELECTED NODES (${selected.length}) ===\n`;
        selected.forEach(n => {
            contextContent += `\nNode: ${n.name}\n  Status: ${n.status || 'N/A'}\n  Roles: ${n.roles || 'N/A'}\n  Age: ${n.age || 'N/A'}\n  Version: ${n.version || 'N/A'}\n  Internal IP: ${n.internalIp || 'N/A'}\n  External IP: ${n.externalIp || 'N/A'}\n`;
        });
    }

    attachedFiles.push({
        name: `${isPods ? 'pods' : 'nodes'}-selection (${selected.length}).txt`,
        size: contextContent.length,
        type: 'text/plain',
        content: contextContent,
        _scanContext: true
    });

    updateAttachmentsPreview();
    closeResourcePicker();
});

attachBrowsePodsOption?.addEventListener('click', () => {
    toggleAttachDropdown(true);
    openResourcePicker('pods');
});

attachBrowseNodesOption?.addEventListener('click', () => {
    toggleAttachDropdown(true);
    openResourcePicker('nodes');
});

// Patch toggleAttachDropdown to also update browse options
const _origToggleAttach = toggleAttachDropdown;
toggleAttachDropdown = function(forceClose) {
    _origToggleAttach(forceClose);
    if (!forceClose && attachDropdown?.style.display !== 'none') {
        const hasPods = Array.isArray(lastScannedPods) && lastScannedPods.length > 0;
        const hasNodes = Array.isArray(lastScannedNodes) && lastScannedNodes.length > 0;
        if (attachBrowsePodsOption) {
            attachBrowsePodsOption.disabled = !hasPods;
            const desc = attachBrowsePodsOption.querySelector('.attach-dropdown-desc');
            if (desc) desc.textContent = hasPods ? `${lastScannedPods.length} pods available` : 'Scan pods first';
        }
        if (attachBrowseNodesOption) {
            attachBrowseNodesOption.disabled = !hasNodes;
            const desc = attachBrowseNodesOption.querySelector('.attach-dropdown-desc');
            if (desc) desc.textContent = hasNodes ? `${lastScannedNodes.length} nodes available` : 'Scan nodes first';
        }
    }
};

function formatFileSize(bytes) {
    if (bytes === 0) return '0 B';
    const k = 1024;
    const sizes = ['B', 'KB', 'MB', 'GB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return Math.round((bytes / Math.pow(k, i)) * 100) / 100 + ' ' + sizes[i];
}

function getFileIcon(filename) {
    const ext = filename.split('.').pop().toLowerCase();
    const icons = {
        log: '📋', txt: '📄', yaml: '⚙️', yml: '⚙️', json: '{ }',
        md: '📝', sh: '🔧', py: '🐍', js: '📜', java: '☕',
        go: '🐹', rs: '🦀', cpp: '⚡', c: '©️', h: '📍',
        ts: '📘', tsx: '⚛️', jsx: '⚛️'
    };
    return icons[ext] || '📎';
}

function updateAttachmentsPreview() {
    attachmentsPreview.innerHTML = '';
    
    attachedFiles.forEach((file, index) => {
        const item = document.createElement('div');
        item.className = 'attachment-item';
        const icon = file._scanContext ? '🔍' : getFileIcon(file.name);
        item.innerHTML = `
            <span class="file-icon">${icon}</span>
            <span class="file-name" title="${file.name}">${file.name}</span>
            <span class="file-size">${formatFileSize(file.size)}</span>
            <button type="button" class="remove-attachment" data-index="${index}" title="Remove file">✕</button>
        `;
        attachmentsPreview.appendChild(item);
    });

    attachmentsPreview.querySelectorAll('.remove-attachment').forEach(btn => {
        btn.addEventListener('click', (e) => {
            e.preventDefault();
            const index = parseInt(btn.dataset.index);
            attachedFiles.splice(index, 1);
            updateAttachmentsPreview();
        });
    });
}

fileInput.addEventListener('change', async (e) => {
    const files = Array.from(e.target.files);
    
    for (const file of files) {
        if (file.size > 5 * 1024 * 1024) {
            alert(`File ${file.name} is too large (max 5MB)`);
            continue;
        }

        try {
            const content = await new Promise((resolve, reject) => {
                const reader = new FileReader();
                reader.onload = (event) => resolve(event.target.result);
                reader.onerror = reject;
                reader.readAsText(file);
            });

            attachedFiles.push({
                name: file.name,
                size: file.size,
                type: file.type,
                content: content
            });
        } catch (error) {
            console.error(`Failed to read file ${file.name}:`, error);
            alert(`Failed to read file ${file.name}`);
        }
    }

    updateAttachmentsPreview();
    fileInput.value = '';
});

// ==================== PODS SCANNER STYLING ====================

const styleSheet = document.createElement('style');
styleSheet.textContent = `
.pod-item, .node-item {
    border: 2px solid var(--ash-grey);
    border-radius: 12px;
    padding: 16px;
    margin: 12px 0;
    background: rgba(255,255,255,0.08);
    transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
}

.pod-item:hover, .node-item:hover {
    border-color: var(--yellow-green);
    background: rgba(255,255,255,0.12);
    box-shadow: 0 4px 16px rgba(133, 203, 51, 0.15);
}

.pod-item h4, .node-item h4 {
    color: var(--yellow-green);
    margin: 0 0 12px 0;
    font-size: 1.1rem;
}

.pod-info, .node-info {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
    gap: 10px;
    margin-bottom: 12px;
}

.pod-info-item, .node-info-item {
    display: flex;
    flex-direction: column;
    gap: 4px;
    padding: 8px;
    background: var(--frosted-mint);
    border-radius: 8px;
    border-left: 3px solid var(--yellow-green);
}

.pod-info-label, .node-info-label {
    font-weight: 600;
    font-size: 0.85rem;
    color: var(--pitch-black);
    text-transform: uppercase;
    opacity: 0.7;
    letter-spacing: 0.3px;
}

.pod-item span, .node-item span {
    color: var(--pitch-black);
}

.btn-scan {
    display: inline-flex;
    align-items: center;
    gap: 0.5rem;
    padding: 0.9rem 1.5rem;
    background: linear-gradient(135deg, var(--yellow-green) 0%, #9de64f 100%);
    color: var(--pitch-black);
    border: 2px solid var(--yellow-green);
    border-radius: 10px;
    cursor: pointer;
    font-weight: 700;
    font-size: 1rem;
    transition: all 0.3s cubic-bezier(0.4, 0, 0.2, 1);
    letter-spacing: 0.3px;
}

.btn-scan:hover {
    transform: translateY(-3px);
    box-shadow: 0 8px 20px rgba(133, 203, 51, 0.4);
}

.btn-scan:active {
    transform: translateY(-1px);
}

.btn-scan:disabled {
    opacity: 0.6;
    cursor: not-allowed;
    transform: none;
}

.scan-loading {
    text-align: center;
    padding: 2rem;
    color: var(--frosted-mint);
    font-size: 1.1rem;
}

.scan-results {
    margin-top: 1.5rem;
}

.scan-results h3 {
    color: var(--yellow-green);
    font-size: 1.3rem;
    margin-bottom: 1rem;
    border-bottom: 2px solid var(--yellow-green);
    padding-bottom: 0.5rem;
}

.pods-list, .nodes-list {
    display: flex;
    flex-direction: column;
    gap: 0.5rem;
}

.chat-history {
    padding: 2rem;
    height: 100%;
    overflow-y: auto;
}

.chat-history h2 {
    color: var(--yellow-green);
    font-size: 1.8rem;
    margin-bottom: 1.5rem;
}

.settings-panel {
    padding: 2rem;
}

.settings-panel h2 {
    color: var(--yellow-green);
    margin-bottom: 1.5rem;
}

.settings-group {
    display: flex;
    flex-direction: column;
    gap: 1rem;
}

.settings-group label {
    display: flex;
    align-items: center;
    gap: 0.75rem;
    color: var(--frosted-mint);
    cursor: pointer;
    padding: 0.75rem;
    border-radius: 8px;
    transition: all 0.3s;
}

.settings-group label:hover {
    background: rgba(133, 203, 51, 0.1);
}

.settings-group input[type="checkbox"] {
    cursor: pointer;
    width: 20px;
    height: 20px;
    accent-color: var(--yellow-green);
}
`;
document.head.appendChild(styleSheet);

console.log('Kubexplain Chat App Loaded! 🚀');