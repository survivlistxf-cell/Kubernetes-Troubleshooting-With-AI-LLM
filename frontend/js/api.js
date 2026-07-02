import { API_URL } from './state.js';
import { parseJsonSafely } from './utils.js';

/**
 * Returns the Authorization header for the current session (empty object when
 * not logged in). The backend protects every /api/** route except auth/health
 * with JWT, so all API calls must carry the Bearer token.
 */
export function authHeaders() {
  const token = localStorage.getItem('authToken');
  return token ? { Authorization: `Bearer ${token}` } : {};
}

/** Clears the stored session and lets the rest of the app react (show login). */
function handleUnauthorized() {
  localStorage.removeItem('authToken');
  localStorage.removeItem('currentUser');
  localStorage.removeItem('userId');
  window.dispatchEvent(new CustomEvent('auth:expired'));
}

async function fetchJson(url, opts = {}) {
  const merged = {
    ...opts,
    headers: { ...authHeaders(), ...(opts.headers || {}) },
  };
  const resp = await fetch(url, merged);
  if (resp.status === 401 && !url.includes('/auth/')) handleUnauthorized();
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

// ---- AI runtime configuration (RAG ablation mode) ----

export async function getRagMode() {
  return fetchJson(`${API_URL}/ai/rag-mode`);
}

export async function setRagMode(mode) {
  return fetchJson(`${API_URL}/ai/rag-mode?value=${encodeURIComponent(mode)}`, { method: 'POST' });
}

// ---- Cluster management API ----

export async function getClusters() {
  return fetchJson(`${API_URL}/clusters`);
}

// folosim multipart/form-data pentru a trimite fisier prin json (care e un fisier de date binare)
// in timp ce json trimite date prin text
export async function addCluster(formData) {
  const resp = await fetch(`${API_URL}/clusters`, {
    method: 'POST',
    headers: authHeaders(), // no Content-Type — the browser sets the multipart boundary
    body: formData,
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

// ---- Topology API ----

export async function getTopology(refresh = false) {
  const params = new URLSearchParams();
  if (refresh) params.set('refresh', 'true');
  const qs = params.toString();
  return fetchJson(`${API_URL}/clusters/topology${qs ? '?' + qs : ''}`);
}

export async function getClusterLinks() {
  return fetchJson(`${API_URL}/cluster-links`);
}

export async function testClusterLink(id) {
  return fetchJson(`${API_URL}/cluster-links/${encodeURIComponent(id)}/test`, { method: 'POST' });
}

export async function discoverClusterLinks(force = false) {
  const qs = force ? '?force=true' : '';
  return fetchJson(`${API_URL}/clusters/discover-links${qs}`, { method: 'POST' });
}

// ---- Multi-cluster scan API ----

export async function scanPodsMulti(clusterIds, namespaceOrMap) {
  const params = new URLSearchParams();
  params.set('clusterIds', (clusterIds || []).join(','));
  if (namespaceOrMap && typeof namespaceOrMap === 'object') {
    const namespaceMap = namespaceOrMap instanceof Map
      ? Object.fromEntries(namespaceOrMap.entries())
      : namespaceOrMap;
    params.set('namespaceMap', JSON.stringify(namespaceMap));
  } else {
    params.set('namespace', namespaceOrMap || 'default');
  }
  return fetchJson(`${API_URL}/scan-pods/multi?${params.toString()}`);
}

export async function scanNodesMulti(clusterIds) {
  const params = new URLSearchParams();
  params.set('clusterIds', (clusterIds || []).join(','));
  return fetchJson(`${API_URL}/scan-nodes/multi?${params.toString()}`);
}
