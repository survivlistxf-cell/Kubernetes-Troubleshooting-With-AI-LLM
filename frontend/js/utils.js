import { state } from './state.js';

// anti xss (cross site scripting)
export function escapeHtml(str) {
  return String(str ?? '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');
}

export function formatFileSize(bytes) {
  const n = Number(bytes);
  if (!Number.isFinite(n) || n < 0) return '';
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  if (n < 1024 * 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(1)} MB`;
  return `${(n / (1024 * 1024 * 1024)).toFixed(1)} GB`;
}

export async function parseJsonSafely(resp) {
  try {
    return await resp.json();
  } catch {
    return null;
  }
}

export function generateConversationId() {
  if (typeof crypto !== 'undefined' && crypto.randomUUID) return crypto.randomUUID();
  return `conv_${Date.now()}_${Math.random().toString(16).slice(2)}`;
}

//ca sa stii mere in ce conversatie te aflii
export function getOrCreateConversationId() {
  const key = state.conversationIdKey; //=conversation_id
  let id = localStorage.getItem(key);
  //intra aici daca apesi pe new chat si nu mai e id in local storage
  //sau daca e prima data cand intri pe site
  if (!id) {
    id = generateConversationId();
    localStorage.setItem(key, id);
  }
  return id;
}

export function formatTimestamp(value) {
  if (!value) return '';
  try {
    const d = new Date(value);
    if (Number.isNaN(d.getTime())) return String(value);
    return d.toLocaleString();
  } catch {
    return String(value);
  }
}

export function prettyJson(value) {
  try {
    if (value == null) return '';
    if (typeof value === 'string') {
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

export function deriveTitleFrom(c) {
  const m = c?.title || c?.userMessage || c?.user_message || '';
  if (!m) return '';
  const one = String(m).replace(/\r?\n/g, ' ').trim();
  return one.length <= 60 ? one : one.substring(0, 57) + '...';
}
