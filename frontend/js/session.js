import { state } from './state.js';
import { switchToTab, hideWelcomeHeader } from './navigation.js';
import { getConversationMessages } from './api.js';
import { addMessage, addUserMessageWithAttachments } from './chat.js';

const KEYS = {
  tab: 'kubexplain.lastTab',
  podsScan: 'kubexplain.lastPodsScan',
  nodesScan: 'kubexplain.lastNodesScan',
};

const VALID_TABS = new Set(['home', 'chat', 'pods', 'nodes', 'settings']);

export function saveActiveTab(tabName) {
  if (!VALID_TABS.has(tabName)) return;
  localStorage.setItem(KEYS.tab, tabName);
}

function getSavedTab() {
  const t = localStorage.getItem(KEYS.tab);
  return VALID_TABS.has(t) ? t : null;
}

export function savePodsScan(snapshot) {
  try {
    localStorage.setItem(KEYS.podsScan, JSON.stringify(snapshot));
  } catch {}
}

export function clearPodsScan() {
  localStorage.removeItem(KEYS.podsScan);
}

export function readSavedPodsScan() {
  try {
    const raw = localStorage.getItem(KEYS.podsScan);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

export function saveNodesScan(snapshot) {
  try {
    localStorage.setItem(KEYS.nodesScan, JSON.stringify(snapshot));
  } catch {}
}

export function clearNodesScan() {
  localStorage.removeItem(KEYS.nodesScan);
}

export function readSavedNodesScan() {
  try {
    const raw = localStorage.getItem(KEYS.nodesScan);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

async function restoreConversation() {
  const userId = localStorage.getItem('userId');
  const convId = localStorage.getItem(state.conversationIdKey);
  if (!userId || !convId) return;

  try {
    const { resp, data } = await getConversationMessages(convId, userId);
    if (!resp.ok) return;

    const thread = (data?.chats || []).slice();
    if (!thread.length) return;

    const messagesArea = document.getElementById('messages');
    if (!messagesArea) return;
    messagesArea.innerHTML = '';
    hideWelcomeHeader();
    state.isFirstMessage = false;

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
  } catch {
    /* silent */
  }
}

export async function restoreSession() {
  // 1) Restore active tab BEFORE async work so the user lands on the right page
  const tab = getSavedTab();
  if (tab && tab !== 'home') switchToTab(tab);

  // 2) Restore previous conversation messages (if any)
  await restoreConversation();
}
