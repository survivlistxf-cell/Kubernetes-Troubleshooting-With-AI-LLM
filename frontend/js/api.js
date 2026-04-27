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

export async function scanPods(namespace, clusterId) {
  const params = new URLSearchParams();
  params.set('namespace', namespace || 'default');
  if (clusterId) params.set('clusterId', clusterId);
  return fetchJson(`${API_URL}/scan-pods?${params.toString()}`);
}

export async function podDetails(namespace, name, type, clusterId) {
  const params = new URLSearchParams();
  if (namespace) params.set('namespace', namespace);
  if (name) params.set('name', name);
  if (type) params.set('type', type);
  if (clusterId) params.set('clusterId', clusterId);
  return fetchJson(`${API_URL}/pod-details?${params.toString()}`);
}

export async function scanNodes(clusterId) {
  const params = new URLSearchParams();
  if (clusterId) params.set('clusterId', clusterId);
  return fetchJson(`${API_URL}/scan-nodes?${params.toString()}`);
}

export async function nodeDetails(name, type, clusterId) {
  const params = new URLSearchParams();
  if (name) params.set('name', name);
  if (type) params.set('type', type);
  if (clusterId) params.set('clusterId', clusterId);
  return fetchJson(`${API_URL}/node-details?${params.toString()}`);
}

export async function fetchAttachmentContent(id) {
  return fetchJson(`${API_URL}/chat/attachments/${encodeURIComponent(id)}/content`);
}

// ---- Cluster management API ----

export async function getClusters() {
  return fetchJson(`${API_URL}/clusters`);
}

export async function addCluster(formData) {
  const resp = await fetch(`${API_URL}/clusters`, {
    method: 'POST',
    body: formData, // multipart/form-data — no Content-Type header (browser sets boundary)
  });
  const data = await parseJsonSafely(resp);
  return { resp, data };
}

export async function updateCluster(id, payload) {
  return fetchJson(`${API_URL}/clusters/${encodeURIComponent(id)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });
}

export async function deleteCluster(id) {
  return fetchJson(`${API_URL}/clusters/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

export async function testCluster(id) {
  return fetchJson(`${API_URL}/clusters/${encodeURIComponent(id)}/test`, { method: 'POST' });
}

export async function getClusterNamespaces(id) {
  return fetchJson(`${API_URL}/clusters/${encodeURIComponent(id)}/namespaces`);
}
