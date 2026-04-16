import { API_URL } from './state.js';
import { parseJsonSafely } from './utils.js';

async function fetchJson(url, opts = {}) {
  const resp = await fetch(url, opts);
  const data = await parseJsonSafely(resp);
  return { resp, data };
}

export async function postChat(payload) {
  return fetchJson(`${API_URL}/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

export async function getConversations(userId) {
  return fetchJson(`${API_URL}/chat/conversations?userId=${encodeURIComponent(userId)}`);
}

export async function getConversationMessages(conversationId, userId) {
  return fetchJson(`${API_URL}/chat/conversation/${encodeURIComponent(conversationId)}/messages?userId=${encodeURIComponent(userId)}`);
}

export async function deleteConversation(conversationId) {
  return fetchJson(`${API_URL}/chat/conversation/${encodeURIComponent(conversationId)}`, { method: 'DELETE' });
}

export async function patchConversationTitle(conversationId, userId, title) {
  return fetchJson(`${API_URL}/chat/conversation/${encodeURIComponent(conversationId)}/title?userId=${encodeURIComponent(userId)}`, {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ title }),
  });
}

export async function scanPods(namespace) {
  return fetchJson(`${API_URL}/scan-pods?namespace=${encodeURIComponent(namespace)}`);
}

export async function podDetails(namespace, name, type) {
  const params = new URLSearchParams();
  if (namespace) params.set('namespace', namespace);
  if (name) params.set('name', name);
  if (type) params.set('type', type);
  return fetchJson(`${API_URL}/pod-details?${params.toString()}`);
}

export async function scanNodes() {
  return fetchJson(`${API_URL}/scan-nodes`);
}

export async function nodeDetails(name, type) {
  const params = new URLSearchParams();
  if (name) params.set('name', name);
  if (type) params.set('type', type);
  return fetchJson(`${API_URL}/node-details?${params.toString()}`);
}

export async function fetchAttachmentContent(id) {
  return fetchJson(`${API_URL}/chat/attachments/${encodeURIComponent(id)}/content`);
}
