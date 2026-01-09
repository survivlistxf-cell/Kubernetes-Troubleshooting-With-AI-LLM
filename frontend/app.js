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

// Auto-resize textarea
promptInput.addEventListener('input', () => {
    promptInput.style.height = 'auto';
    promptInput.style.height = Math.min(promptInput.scrollHeight, 150) + 'px';
});

// Handle form submission
promptForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    
    const message = promptInput.value.trim();
    if (!message) return;
    
    // Hide welcome header and collapse sidebar on first message
    hideWelcomeHeader();
    autoCollapseSidebar();
    
    // Add user message to chat
    addMessage(message, 'user');
    promptInput.value = '';
    promptInput.style.height = 'auto';
    
    // Show typing indicator
    showTypingIndicator();
    
    try {
        // Send to backend API
        const payload = { message };
        const userId = localStorage.getItem('userId');
        if (userId) {
            payload.userId = userId;
        }

        // Attach active conversation id so the backend can persist and AI can keep context.
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
            // Backend returns the conversationId it actually used. Keep it in sync.
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
    contentDiv.textContent = text;
    
    messageDiv.appendChild(avatarDiv);
    messageDiv.appendChild(contentDiv);
    
    messagesArea.appendChild(messageDiv);
    
    // Scroll to bottom
    messagesArea.scrollTop = messagesArea.scrollHeight;
}

// Show typing indicator
function showTypingIndicator() {
    const messageDiv = document.createElement('div');
    messageDiv.className = 'message assistant typing-indicator';
    messageDiv.id = 'typing-indicator';
    
    const avatarDiv = document.createElement('div');
    avatarDiv.className = 'message-avatar';
    avatarDiv.textContent = 'AI';
    
    const contentDiv = document.createElement('div');
    contentDiv.className = 'message-content';
    contentDiv.innerHTML = '<span></span><span></span><span></span>';
    
    messageDiv.appendChild(avatarDiv);
    messageDiv.appendChild(contentDiv);
    
    messagesArea.appendChild(messageDiv);
    messagesArea.scrollTop = messagesArea.scrollHeight;
}

// Remove typing indicator
function removeTypingIndicator() {
    const indicator = document.getElementById('typing-indicator');
    if (indicator) {
        indicator.remove();
    }
}

// Pods Scanner Functionality
const scanBtn = document.getElementById('scan-btn');
const scanResults = document.getElementById('scan-results');
const scanLoading = document.getElementById('scan-loading');
const podsList = document.getElementById('pods-list');
const podsAddToChatBtn = document.getElementById('pods-add-to-chat-btn');

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
        const response = await fetch(`${API_URL}/scan-pods`, {
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
                    `;
                    podsList.appendChild(podDiv);
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

podsAddToChatBtn?.addEventListener('click', async () => {
    if (!podsAddToChatBtn) return;

    console.log('[pods] Add context clicked', {
        hasPods: Array.isArray(lastScannedPods) && lastScannedPods.length > 0,
        podsCount: Array.isArray(lastScannedPods) ? lastScannedPods.length : 0
    });

    podsAddToChatBtn.disabled = true;
    podsAddToChatBtn.style.opacity = '0.6';
    try {
        if (!lastScannedPods) {
            lastScannedPods = [];
        }

        // Level 0 for now; server-side can be extended to fetch events/logs/describe on demand.
        const payload = buildPodsContextPayload(0, lastScannedPods);
        console.log('[pods] Sending context payload to server', payload);
        await sendPodsContextToServer(payload);

        // Switch to the chat (Home) tab and show an action confirmation.
        hideWelcomeHeader();
        autoCollapseSidebar();
        document.querySelectorAll('.nav-item').forEach(btn => btn.classList.remove('active'));
        document.querySelectorAll('.tab-content').forEach(tab => tab.classList.remove('active'));
        const homeBtn = document.querySelector('.nav-item[data-tab="home"]');
        const homeTab = document.getElementById('home');
        if (homeBtn) homeBtn.classList.add('active');
        if (homeTab) homeTab.classList.add('active');

        addMessage('✅ Pods context added', 'user');
    } catch (error) {
        console.error('[pods] Failed to add context', error);

        // Still switch to chat so user sees feedback.
        hideWelcomeHeader();
        autoCollapseSidebar();
        document.querySelectorAll('.nav-item').forEach(btn => btn.classList.remove('active'));
        document.querySelectorAll('.tab-content').forEach(tab => tab.classList.remove('active'));
        const homeBtn = document.querySelector('.nav-item[data-tab="home"]');
        const homeTab = document.getElementById('home');
        if (homeBtn) homeBtn.classList.add('active');
        if (homeTab) homeTab.classList.add('active');

        addMessage(`❌ Pods context NOT added: ${error?.message || 'unknown error'}`, 'user');
    } finally {
        podsAddToChatBtn.disabled = false;
        podsAddToChatBtn.style.opacity = '1';
    }
});

// Nodes Scanner Functionality
const scanNodesBtn = document.getElementById('scan-nodes-btn');
const nodesScanResults = document.getElementById('nodes-scan-results');
const nodesScanLoading = document.getElementById('nodes-scan-loading');
const nodesList = document.getElementById('nodes-list');

scanNodesBtn.addEventListener('click', async () => {
    scanNodesBtn.disabled = true;
    scanNodesBtn.style.opacity = '0.6';
    nodesScanLoading.style.display = 'block';
    nodesScanResults.style.display = 'none';
    nodesList.innerHTML = '';

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
                    `;
                    nodesList.appendChild(nodeDiv);
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

console.log('Kubexplain Chat App Loaded! 🚀');