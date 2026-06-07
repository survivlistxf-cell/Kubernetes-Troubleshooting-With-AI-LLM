// Topology tab — graph view of clusters + auto-detected links, multi-cluster scan basket.

import { state } from './state.js';
import {
  getTopology,
  discoverClusterLinks,
  testClusterLink,
  scanPodsMulti,
  scanNodesMulti,
  podDetails,
  nodeDetails,
  getClusterNamespaces,
} from './api.js';
import { escapeHtml } from './utils.js';
import { addAttachment } from './attachments.js';
import { switchToTab } from './navigation.js';

const MAX_BASKET = 5;
const TOPOLOGY_NAMESPACE_MODE_KEY = 'kubexplain.topologyBasketNamespaceMode';
const TOPOLOGY_NAMESPACE_SELECTIONS_KEY = 'kubexplain.topologyBasketNamespacesByCluster';

let topologyNamespaceMode = localStorage.getItem(TOPOLOGY_NAMESPACE_MODE_KEY) === 'cluster'
  ? 'cluster'
  : 'shared';

let topologyNamespaceSelections = loadTopologyNamespaceSelections();

function loadTopologyNamespaceSelections() {
  try {
    const raw = localStorage.getItem(TOPOLOGY_NAMESPACE_SELECTIONS_KEY);
    const parsed = raw ? JSON.parse(raw) : {};
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};
  } catch {
    return {};
  }
}

function saveTopologyNamespaceSelections() {
  try {
    localStorage.setItem(TOPOLOGY_NAMESPACE_SELECTIONS_KEY, JSON.stringify(topologyNamespaceSelections));
  } catch {}
}

function getTopologyNamespaceSelection(clusterId, fallback = 'default') {
  const value = topologyNamespaceSelections[String(clusterId)];
  return (value != null && String(value).trim()) ? String(value).trim() : fallback;
}

function setTopologyNamespaceSelection(clusterId, value) {
  const nextValue = value != null ? String(value).trim() : '';
  const key = String(clusterId);
  if (nextValue) topologyNamespaceSelections[key] = nextValue;
  else delete topologyNamespaceSelections[key];
  saveTopologyNamespaceSelections();
}

const topo = {
  data: { clusters: [], links: [] },
  basket: new Set(),
  selection: null,
  // Separate scan state so both pod + node results persist independently
  scans: { pods: null, nodes: null },
  selectedPods: new Set(),
  selectedNodes: new Set(),
  initialized: false,
};

const STATUS_COLORS = {
  connected: { color: '#3aa028', label: '✅ connected' },
  failed:    { color: '#c4392b', label: '❌ failed' },
  testing:   { color: '#d99411', label: '⏳ testing' },
  unknown:   { color: '#7d8aa3', label: '— unknown' },
};

// ============================================================
// Public init
// ============================================================

export function initTopology() {
  document.getElementById('topology-refresh-btn')?.addEventListener('click', () => loadTopology(true));
  document.getElementById('topology-discover-btn')?.addEventListener('click', () => handleDiscover(true));

  document.getElementById('topology-basket-clear')?.addEventListener('click', clearBasket);
  document.getElementById('topology-basket-scan-pods')?.addEventListener('click', () => runMultiScan('pods'));
  document.getElementById('topology-basket-scan-nodes')?.addEventListener('click', () => runMultiScan('nodes'));
  document.getElementById('topology-basket-shared-namespace')?.addEventListener('change', (ev) => {
    topologyNamespaceMode = ev.target.checked ? 'shared' : 'cluster';
    try {
      localStorage.setItem(TOPOLOGY_NAMESPACE_MODE_KEY, topologyNamespaceMode);
    } catch {}
    refreshBasketView();
  });

  // On first tab open: load topology, then trigger discovery in background.
  document.querySelectorAll('.nav-item[data-tab="topology"]').forEach(btn => {
    btn.addEventListener('click', () => {
      if (!topo.initialized) {
        topo.initialized = true;
        loadTopology(false).then(() => handleDiscover(false));
      }
    });
  });
}

// ============================================================
// Data loading + graph rendering
// ============================================================

async function loadTopology(forceRefresh) {
  const loadingEl = document.getElementById('topology-loading');
  if (loadingEl) loadingEl.style.display = 'flex';

  try {
    const { resp, data } = await getTopology(!!forceRefresh);
    if (!resp.ok) {
      renderTopologyError(data?.error || 'Failed to load topology');
      return;
    }
    topo.data.clusters = Array.isArray(data?.clusters) ? data.clusters : [];
    topo.data.links = Array.isArray(data?.links) ? data.links : [];
    renderGraph();
    refreshBasketView();
    if (topo.selection) {
      if (topo.selection.kind === 'cluster') showClusterDetails(topo.selection.id);
      else if (topo.selection.kind === 'link') showLinkDetails(topo.selection.id);
    }
  } catch (err) {
    console.error('Topology load error', err);
    renderTopologyError(err.message);
  } finally {
    if (loadingEl) loadingEl.style.display = 'none';
  }
}

function renderTopologyError(msg) {
  const canvas = document.getElementById('topology-canvas');
  if (!canvas) return;
  canvas.innerHTML = `<div class="topology-loading"><p style="color:#c4392b;">⚠️ ${escapeHtml(String(msg))}</p></div>`;
}

function renderGraph() {
  const container = document.getElementById('topology-canvas');
  if (!container || !window.vis) {
    console.warn('vis-network not loaded yet');
    return;
  }

  container.innerHTML = '';

  const nodes = topo.data.clusters.map(c => buildNodeForCluster(c));
  const edges = topo.data.links.map(l => buildEdgeForLink(l));

  const data = {
    nodes: new vis.DataSet(nodes),
    edges: new vis.DataSet(edges),
  };
  const options = {
    nodes: {
      shape: 'box',
      borderWidth: 2,
      shadow: { enabled: true, color: 'rgba(0,0,0,0.10)', size: 8, x: 2, y: 2 },
      margin: 12,
      font: { multi: 'html', size: 13, color: '#0f172a', face: 'Inter, system-ui' },
    },
    edges: {
      width: 2,
      smooth: { type: 'continuous' },
      arrows: { to: { enabled: true, scaleFactor: 0.7 } },
      font: { align: 'middle', size: 11, color: '#475569', strokeWidth: 4, strokeColor: '#ffffff' },
    },
    physics: {
      enabled: true,
      stabilization: { iterations: 200 },
      barnesHut: { gravitationalConstant: -8000, springLength: 180 },
    },
    interaction: { hover: true, dragNodes: true, zoomView: true, navigationButtons: false },
  };

  const network = new vis.Network(container, data, options);

  network.on('selectNode', (params) => {
    const id = params.nodes?.[0];
    if (id != null) showClusterDetails(Number(id));
  });
  network.on('selectEdge', (params) => {
    const eid = params.edges?.[0];
    if (eid != null) showLinkDetails(Number(eid));
  });
  network.on('deselectNode', () => clearSidePanel());
  network.on('deselectEdge', () => clearSidePanel());

  if (!nodes.length) {
    container.innerHTML = `
      <div class="topology-loading">
        <p>No clusters configured yet.</p>
        <p style="opacity:0.7; font-size:0.9rem;">Add some clusters from the Settings → Clusters page first.</p>
      </div>`;
  } else if (!edges.length) {
    // Clusters are visible on the graph — just show a note that no links were found yet.
    const hint = document.createElement('div');
    hint.style.cssText = 'position:absolute;bottom:12px;left:12px;font-size:0.8rem;color:var(--text-muted);pointer-events:none;';
    hint.textContent = 'No cross-cluster links detected. Click "Discover Links" to scan.';
    container.style.position = 'relative';
    container.appendChild(hint);
  }
}

function buildNodeForCluster(c) {
  const status = c.status || 'unknown';
  const statusInfo = STATUS_COLORS[status] || STATUS_COLORS.unknown;
  const counts = (c.podCount != null || c.nodeCount != null)
    ? `<i>${c.nodeCount ?? '?'} nodes · ${c.podCount ?? '?'} pods</i>`
    : '<i>counts unavailable</i>';

  const label = `<b>${escapeHtml(c.displayName || c.name)}</b>\n${counts}\n${statusInfo.label}`;

  return {
    id: c.id,
    label,
    color: {
      background: c.isDefault ? '#fff7d4' : '#ffffff',
      border: statusInfo.color,
      highlight: { background: '#eaf7d4', border: '#85cb33' },
    },
  };
}

function buildEdgeForLink(l) {
  const status = (l.lastTestStatus || 'unknown').toLowerCase();
  const colorInfo = STATUS_COLORS[status] || STATUS_COLORS.unknown;
  const dashes = status === 'failed' || status === 'unknown';
  const label = l.source || l.type || '';

  return {
    id: l.id,
    from: l.sourceId,
    to: l.targetId,
    label,
    color: { color: colorInfo.color, highlight: colorInfo.color },
    dashes,
  };
}

// ============================================================
// Discovery
// ============================================================

async function handleDiscover(force) {
  const btn = document.getElementById('topology-discover-btn');
  if (btn) { btn.disabled = true; btn.textContent = '⏳ Discovering…'; }
  try {
    const { resp, data } = await discoverClusterLinks(force);
    if (!resp.ok) {
      console.error('Discovery failed', data);
    }
    await loadTopology(false);
  } catch (err) {
    console.error('Discovery error', err);
  } finally {
    if (btn) { btn.disabled = false; btn.textContent = '🔍 Discover Links'; }
  }
}

// ============================================================
// Side panel
// ============================================================

function clearSidePanel() {
  topo.selection = null;
  const empty = document.getElementById('topology-side-empty');
  const content = document.getElementById('topology-side-content');
  if (empty) empty.hidden = false;
  if (content) {
    content.hidden = true;
    content.innerHTML = '';
  }
}

function setSidePanelContent(html) {
  const empty = document.getElementById('topology-side-empty');
  const content = document.getElementById('topology-side-content');
  if (empty) empty.hidden = true;
  if (content) {
    content.hidden = false;
    content.innerHTML = html;
  }
}

function showClusterDetails(id) {
  const c = topo.data.clusters.find(x => Number(x.id) === Number(id));
  if (!c) return;
  topo.selection = { kind: 'cluster', id: c.id };

  const status = c.status || 'unknown';
  const statusPill = `<span class="topology-status-pill ${status}">${STATUS_COLORS[status]?.label || status}</span>`;

  const inBasket = topo.basket.has(Number(c.id));
  const basketBtn = inBasket
    ? `<button class="btn-secondary topology-basket-toggle" data-id="${c.id}">➖ Remove from basket</button>`
    : `<button class="btn-primary topology-basket-toggle" data-id="${c.id}">➕ Add to basket</button>`;

  const html = `
    <h3>☁️ ${escapeHtml(c.displayName || c.name)} ${statusPill}</h3>
    <div class="topology-side-meta">
      <span><strong>Name:</strong> ${escapeHtml(c.name)}</span>
      ${c.isDefault ? '<span><strong>⭐ Default</strong></span>' : ''}
      <span><strong>NS:</strong> ${escapeHtml(c.defaultNamespace || 'default')}</span>
      ${c.nodeCount != null ? `<span>${c.nodeCount} nodes</span>` : ''}
      ${c.podCount != null ? `<span>${c.podCount} pods</span>` : ''}
    </div>
    <div class="topology-side-actions">
      ${basketBtn}
      <button class="btn-secondary topology-go-pods" data-id="${c.id}">📦 Go to Pods</button>
      <button class="btn-secondary topology-go-nodes" data-id="${c.id}">🖥️ Go to Nodes</button>
    </div>
  `;
  setSidePanelContent(html);

  document.querySelectorAll('.topology-basket-toggle').forEach(b =>
    b.addEventListener('click', () => toggleBasket(Number(b.dataset.id))));
  document.querySelector('.topology-go-pods')?.addEventListener('click', (ev) => {
    state.activeClusterId = Number(ev.currentTarget.dataset.id);
    persistClusterSelectorChoice('pods-cluster-select', state.activeClusterId);
    switchToTab('pods');
  });
  document.querySelector('.topology-go-nodes')?.addEventListener('click', (ev) => {
    state.activeClusterId = Number(ev.currentTarget.dataset.id);
    persistClusterSelectorChoice('nodes-cluster-select', state.activeClusterId);
    switchToTab('nodes');
  });
}

function persistClusterSelectorChoice(selectId, clusterId) {
  const sel = document.getElementById(selectId);
  if (!sel) return;
  if ([...sel.options].some(o => o.value === String(clusterId))) {
    sel.value = String(clusterId);
    sel.dispatchEvent(new Event('change', { bubbles: true }));
  }
}

function showLinkDetails(id) {
  const l = topo.data.links.find(x => Number(x.id) === Number(id));
  if (!l) return;
  topo.selection = { kind: 'link', id: l.id };

  const src = topo.data.clusters.find(c => Number(c.id) === Number(l.sourceId));
  const dst = topo.data.clusters.find(c => Number(c.id) === Number(l.targetId));
  const status = (l.lastTestStatus || 'unknown').toLowerCase();
  const statusPill = `<span class="topology-status-pill ${status}">${STATUS_COLORS[status]?.label || status}</span>`;

  const lastTested = l.lastTestAt
    ? new Date(l.lastTestAt).toLocaleString()
    : 'never';

  const html = `
    <h3>🔗 Link #${l.id} ${statusPill}</h3>
    <div class="topology-side-meta">
      <span><strong>From:</strong> ${escapeHtml(src?.displayName || src?.name || '?')}</span>
      <span><strong>To:</strong> ${escapeHtml(dst?.displayName || dst?.name || '?')}</span>
      <span><strong>Detector:</strong> ${escapeHtml(l.source || l.type || 'unknown')}</span>
    </div>
    <p style="font-size:0.8rem; color:var(--text-muted);">Last tested: ${escapeHtml(lastTested)}</p>
    ${l.lastTestMessage ? `<details style="margin-top:0.4rem;"><summary style="cursor:pointer;">Last result</summary><pre style="white-space:pre-wrap; font-size:0.8rem; max-height:160px; overflow:auto;">${escapeHtml(l.lastTestMessage)}</pre></details>` : ''}
    <div class="topology-side-actions">
      <button class="btn-primary" id="topology-link-test">🧪 Test now</button>
    </div>
  `;
  setSidePanelContent(html);

  document.getElementById('topology-link-test')?.addEventListener('click', () => handleTestLink(l.id));
}

async function handleTestLink(id) {
  const btn = document.getElementById('topology-link-test');
  if (btn) { btn.disabled = true; btn.textContent = '⏳ Testing…'; }
  try {
    const { resp, data } = await testClusterLink(id);
    if (!resp.ok) alert('Test failed: ' + (data?.error || 'unknown'));
  } finally {
    await loadTopology(false);
    if (btn) { btn.disabled = false; btn.textContent = '🧪 Test now'; }
  }
}

// ============================================================
// Multi-cluster basket + scan
// ============================================================

function toggleBasket(clusterId) {
  if (topo.basket.has(clusterId)) {
    topo.basket.delete(clusterId);
  } else {
    if (topo.basket.size >= MAX_BASKET) {
      alert(`Limit reached: max ${MAX_BASKET} clusters in the basket.`);
      return;
    }
    topo.basket.add(clusterId);
  }
  refreshBasketView();
  if (topo.selection?.kind === 'cluster') showClusterDetails(topo.selection.id);
}

function clearBasket() {
  topo.basket.clear();
  refreshBasketView();
  if (topo.selection?.kind === 'cluster') showClusterDetails(topo.selection.id);
}

function refreshBasketView() {
  const wrap = document.getElementById('topology-basket');
  const chips = document.getElementById('topology-basket-chips');
  const count = document.getElementById('topology-basket-count');
  const sharedToggle = document.getElementById('topology-basket-shared-namespace');
  const sharedWrap = document.getElementById('topology-basket-shared-wrap');
  const clusterNsWrap = document.getElementById('topology-basket-cluster-namespaces');
  if (!wrap || !chips || !count) return;

  count.textContent = String(topo.basket.size);
  if (topo.basket.size === 0) {
    wrap.hidden = true;
    chips.innerHTML = '';
    if (clusterNsWrap) clusterNsWrap.hidden = true;
    return;
  }
  wrap.hidden = false;

  if (sharedToggle) sharedToggle.checked = topologyNamespaceMode === 'shared';
  if (sharedWrap) sharedWrap.hidden = topologyNamespaceMode !== 'shared';
  if (clusterNsWrap) clusterNsWrap.hidden = topologyNamespaceMode !== 'cluster';

  chips.innerHTML = [...topo.basket].map(id => {
    const c = topo.data.clusters.find(x => Number(x.id) === id);
    const label = c ? (c.displayName || c.name) : `#${id}`;
    return `<span class="topology-basket-chip">${escapeHtml(label)}<button data-id="${id}" title="Remove">×</button></span>`;
  }).join('');

  chips.querySelectorAll('button').forEach(b =>
    b.addEventListener('click', () => toggleBasket(Number(b.dataset.id))));

  // Refresh namespace dropdown for current basket
  refreshBasketNamespaces();
}

/**
 * Fetches the union of all namespaces from the basket clusters and populates
 * the #topology-basket-namespace <select> element.
 */
async function refreshBasketNamespaces() {
  const sel = document.getElementById('topology-basket-namespace');
  const clusterWrap = document.getElementById('topology-basket-cluster-namespaces');
  if (topo.basket.size === 0) {
    if (clusterWrap) clusterWrap.innerHTML = '';
    return;
  }

  const previousValue = sel?.value || 'default';

  const PREDEFINED = ['default', 'kube-system', 'kube-public', 'kube-node-lease'];
  const basketIds = [...topo.basket];
  const clusterMeta = new Map();
  const namespaceUnion = new Map();
  PREDEFINED.forEach(ns => namespaceUnion.set(ns, new Set()));

  await Promise.all(
    basketIds.map(async id => {
      try {
        const { resp, data } = await getClusterNamespaces(id);
        const namespaces = resp.ok && Array.isArray(data?.namespaces) ? data.namespaces : [];
        const cluster = topo.data.clusters.find(c => Number(c.id) === Number(id));
        const clusterName = cluster?.displayName || cluster?.name || `#${id}`;
        const uniqueNamespaces = Array.from(new Set([...PREDEFINED, ...namespaces])).sort((a, b) => {
          if (a === 'default') return -1;
          if (b === 'default') return 1;
          return a.localeCompare(b);
        });
        clusterMeta.set(Number(id), { clusterName, namespaces: uniqueNamespaces, defaultNamespace: cluster?.defaultNamespace || 'default' });
        namespaces.forEach(ns => {
          if (!namespaceUnion.has(ns)) namespaceUnion.set(ns, new Set());
          namespaceUnion.get(ns).add(clusterName);
        });
      } catch {
        const cluster = topo.data.clusters.find(c => Number(c.id) === Number(id));
        clusterMeta.set(Number(id), {
          clusterName: cluster?.displayName || cluster?.name || `#${id}`,
          namespaces: PREDEFINED,
          defaultNamespace: cluster?.defaultNamespace || 'default',
        });
      }
    })
  );

  if (sel) {
    const sorted = [...namespaceUnion.keys()].sort((a, b) => {
      if (a === 'default') return -1;
      if (b === 'default') return 1;
      return a.localeCompare(b);
    });

    sel.innerHTML = '';
    sorted.forEach(ns => {
      const clusterNames = [...namespaceUnion.get(ns)];
      const attribution = clusterNames.length > 0 ? ` (${clusterNames.join(', ')})` : '';
      const opt = document.createElement('option');
      opt.value = ns;
      opt.textContent = `${ns}${attribution}`;
      sel.appendChild(opt);
    });

    sel.value = sorted.includes(previousValue) ? previousValue : 'default';
  }

  if (clusterWrap && topologyNamespaceMode === 'cluster') {
    clusterWrap.innerHTML = '';
    basketIds.forEach(id => {
      const meta = clusterMeta.get(Number(id));
      const row = document.createElement('div');
      row.className = 'topology-basket-cluster-namespace';
      row.innerHTML = `
        <span class="topology-basket-cluster-namespace-label">${escapeHtml(meta.clusterName)}</span>
        <select class="form-control topology-basket-cluster-namespace-select" data-cluster-id="${id}"></select>
      `;
      const selectEl = row.querySelector('select');
      if (selectEl) {
        meta.namespaces.forEach(ns => {
          const opt = document.createElement('option');
          opt.value = ns;
          opt.textContent = ns;
          selectEl.appendChild(opt);
        });
        const selectedValue = getTopologyNamespaceSelection(id, meta.defaultNamespace);
        selectEl.value = meta.namespaces.includes(selectedValue) ? selectedValue : meta.defaultNamespace;
        selectEl.addEventListener('change', () => setTopologyNamespaceSelection(id, selectEl.value));
      }
      clusterWrap.appendChild(row);
    });
  } else if (clusterWrap) {
    clusterWrap.innerHTML = '';
  }
}

async function runMultiScan(kind) {
  const ids = [...topo.basket];
  const namespace = (document.getElementById('topology-basket-namespace')?.value || 'default').trim() || 'default';
  const namespaceMap = {};
  if (topologyNamespaceMode === 'cluster' && kind === 'pods') {
    document.querySelectorAll('.topology-basket-cluster-namespace-select').forEach(selectEl => {
      const clusterId = Number(selectEl.dataset.clusterId);
      const chosen = (selectEl.value || 'default').trim() || 'default';
      namespaceMap[String(clusterId)] = chosen;
    });
  }

  // Show a per-kind loading indicator without wiping the other section
  const resultsEl = document.getElementById('topology-multi-results');
  if (resultsEl) resultsEl.hidden = false;

  // Mark the scan as "loading" in state so the rendering shows a spinner in the right section
  topo.scans[kind] = { kind, loading: true };
  if (kind === 'pods') topo.selectedPods.clear();
  else topo.selectedNodes.clear();
  renderMultiScanResults();

  try {
    const { resp, data } = kind === 'pods'
      ? await scanPodsMulti(ids, topologyNamespaceMode === 'cluster' ? namespaceMap : namespace)
      : await scanNodesMulti(ids);
    if (!resp.ok) {
      topo.scans[kind] = { kind, error: data?.error || 'Scan failed' };
      renderMultiScanResults();
      return;
    }
    topo.scans[kind] = {
      kind,
      results: data?.results || [],
      namespace: kind === 'pods' && topologyNamespaceMode === 'shared' ? namespace : null,
      namespaceMap: kind === 'pods' && topologyNamespaceMode === 'cluster' ? namespaceMap : null,
      namespaceMode: kind === 'pods' ? topologyNamespaceMode : null,
    };
    renderMultiScanResults();
  } catch (err) {
    topo.scans[kind] = { kind, error: err.message };
    renderMultiScanResults();
  }
}

// ============================================================
// Multi-cluster scan results rendering (dual-section, column grid)
// ============================================================

function renderMultiScanResults() {
  const el = document.getElementById('topology-multi-results');
  if (!el) return;

  const { pods: podScan, nodes: nodeScan } = topo.scans;
  if (!podScan && !nodeScan) {
    el.hidden = true;
    return;
  }
  el.hidden = false;

  let html = '';
  if (podScan) html += renderScanSection(podScan, topo.selectedPods);
  if (nodeScan) html += renderScanSection(nodeScan, topo.selectedNodes);
  el.innerHTML = html;

  // Wire up pod-section events
  if (podScan && !podScan.loading && !podScan.error) {
    el.querySelectorAll('.topo-cb[data-scan-kind="pods"]').forEach(cb =>
      cb.addEventListener('change', ev => onScanCbChange(ev, 'pods')));
    el.querySelector('#topo-pods-select-all')?.addEventListener('click', () => selectAllScan('pods'));
    el.querySelector('#topo-pods-clear')?.addEventListener('click', () => clearScanSelection('pods'));
    el.querySelector('#topo-pods-add-ctx')?.addEventListener('click', () => addScanContext('pods'));
    el.querySelector('#topo-pods-clear-scan')?.addEventListener('click', () => clearScan('pods'));
    updateScanCount('pods');
  }
  // Wire up node-section events
  if (nodeScan && !nodeScan.loading && !nodeScan.error) {
    el.querySelectorAll('.topo-cb[data-scan-kind="nodes"]').forEach(cb =>
      cb.addEventListener('change', ev => onScanCbChange(ev, 'nodes')));
    el.querySelector('#topo-nodes-select-all')?.addEventListener('click', () => selectAllScan('nodes'));
    el.querySelector('#topo-nodes-clear')?.addEventListener('click', () => clearScanSelection('nodes'));
    el.querySelector('#topo-nodes-add-ctx')?.addEventListener('click', () => addScanContext('nodes'));
    el.querySelector('#topo-nodes-clear-scan')?.addEventListener('click', () => clearScan('nodes'));
    updateScanCount('nodes');
  }
}

function renderScanSection(scan, selectionSet) {
  const kind = scan.kind;
  const icon = kind === 'pods' ? '📦' : '🖥️';
  const idPrefix = kind === 'pods' ? 'topo-pods' : 'topo-nodes';

  if (scan.loading) {
    return `<div class="topology-scan-section">
      <div class="topology-scan-section-header">
        <span class="topology-scan-section-title">${icon} ${kind === 'pods' ? 'Pods' : 'Nodes'} scan</span>
      </div>
      <p>⏳ Scanning…</p>
    </div>`;
  }

  if (scan.error) {
    return `<div class="topology-scan-section">
      <div class="topology-scan-section-header">
        <span class="topology-scan-section-title">${icon} ${kind === 'pods' ? 'Pods' : 'Nodes'} scan</span>
        <button class="btn-helper" id="${idPrefix}-clear-scan">✕ Clear</button>
      </div>
      <p style="color:#c4392b;">❌ ${escapeHtml(scan.error)}</p>
    </div>`;
  }

  const results = scan.results || [];
  const total = results.reduce((acc, r) => acc + (kind === 'pods' ? (r.pods?.length || 0) : (r.nodes?.length || 0)), 0);
  const nsNote = kind === 'pods'
    ? (scan.namespaceMode === 'cluster'
      ? ' · ns: per cluster'
      : (scan.namespace ? ` · ns: <code>${escapeHtml(scan.namespace)}</code>` : ''))
    : '';

  // Build column grid
  let cols = '';
  for (const r of results) {
    const cName = escapeHtml(r.clusterDisplayName || r.clusterName || `Cluster #${r.clusterId}`);
    const items = kind === 'pods' ? (r.pods || []) : (r.nodes || []);

    if (!r.success) {
      cols += `<div class="multi-cluster-col">
        <div class="multi-cluster-col-header">☁️ ${cName}</div>
        <p style="font-size:0.82rem;color:#c4392b;">❌ ${escapeHtml(r.error || 'failed')}</p>
      </div>`;
      continue;
    }
    if (items.length === 0) {
      cols += `<div class="multi-cluster-col">
        <div class="multi-cluster-col-header">☁️ ${cName} (0)</div>
        <p style="font-size:0.82rem;opacity:0.6;">no ${kind} found</p>
      </div>`;
      continue;
    }

    let rows;
    if (kind === 'pods') {
      rows = items.map(p => {
        const key = `${r.clusterId}::${p.namespace}/${p.name}`;
        const checked = selectionSet.has(key) ? 'checked' : '';
        return `<tr>
          <td><input type="checkbox" class="topo-cb" data-scan-kind="${kind}" data-key="${key}" ${checked}/></td>
          <td title="${escapeHtml(p.name)}">${escapeHtml(p.name)}</td>
          <td>${escapeHtml(p.namespace)}</td>
          <td>${escapeHtml(p.status || '')}</td>
          <td>${escapeHtml(String(p.restarts ?? ''))}</td>
        </tr>`;
      }).join('');
      cols += `<div class="multi-cluster-col">
        <div class="multi-cluster-col-header">☁️ ${cName} (${items.length})</div>
        <table class="topology-multi-table">
          <thead><tr><th></th><th>Name</th><th>NS</th><th>Status</th><th>↩</th></tr></thead>
          <tbody>${rows}</tbody>
        </table>
      </div>`;
    } else {
      rows = items.map(n => {
        const key = `${r.clusterId}::${n.name}`;
        const checked = selectionSet.has(key) ? 'checked' : '';
        return `<tr>
          <td><input type="checkbox" class="topo-cb" data-scan-kind="${kind}" data-key="${key}" ${checked}/></td>
          <td title="${escapeHtml(n.name)}">${escapeHtml(n.name)}</td>
          <td>${escapeHtml(n.status || '')}</td>
          <td>${escapeHtml(n.roles || '')}</td>
        </tr>`;
      }).join('');
      cols += `<div class="multi-cluster-col">
        <div class="multi-cluster-col-header">☁️ ${cName} (${items.length})</div>
        <table class="topology-multi-table">
          <thead><tr><th></th><th>Name</th><th>Status</th><th>Roles</th></tr></thead>
          <tbody>${rows}</tbody>
        </table>
      </div>`;
    }
  }

  const gridStyle = `--mc-cols:${results.length}`;

  return `<div class="topology-scan-section">
    <div class="topology-scan-section-header">
      <span class="topology-scan-section-title">${icon} ${kind === 'pods' ? 'Pods' : 'Nodes'} scan · ${total} items${nsNote}</span>
      <button class="btn-helper" id="${idPrefix}-clear-scan" title="Remove this section">✕ Clear</button>
    </div>
    <div class="multi-cluster-grid" style="${gridStyle}">${cols}</div>
    <div class="topology-multi-actions">
      <span class="topology-multi-summary"><strong id="${idPrefix}-count">0</strong> selected</span>
      <button class="btn-helper" id="${idPrefix}-select-all">Select all</button>
      <button class="btn-helper" id="${idPrefix}-clear">Clear selection</button>
      <button class="btn-primary" id="${idPrefix}-add-ctx">➕ Add to context</button>
    </div>
  </div>`;
}

function onScanCbChange(ev, kind) {
  const key = ev.target.dataset.key;
  const set = kind === 'pods' ? topo.selectedPods : topo.selectedNodes;
  if (ev.target.checked) set.add(key);
  else set.delete(key);
  updateScanCount(kind);
}

function updateScanCount(kind) {
  const idPrefix = kind === 'pods' ? 'topo-pods' : 'topo-nodes';
  const set = kind === 'pods' ? topo.selectedPods : topo.selectedNodes;
  const el = document.getElementById(`${idPrefix}-count`);
  if (el) el.textContent = String(set.size);
}

function selectAllScan(kind) {
  const set = kind === 'pods' ? topo.selectedPods : topo.selectedNodes;
  document.querySelectorAll(`.topo-cb[data-scan-kind="${kind}"]`).forEach(cb => {
    cb.checked = true;
    set.add(cb.dataset.key);
  });
  updateScanCount(kind);
}

function clearScanSelection(kind) {
  const set = kind === 'pods' ? topo.selectedPods : topo.selectedNodes;
  document.querySelectorAll(`.topo-cb[data-scan-kind="${kind}"]`).forEach(cb => { cb.checked = false; });
  set.clear();
  updateScanCount(kind);
}

function clearScan(kind) {
  topo.scans[kind] = null;
  if (kind === 'pods') topo.selectedPods.clear();
  else topo.selectedNodes.clear();
  renderMultiScanResults();
}

async function addScanContext(kind) {
  const set = kind === 'pods' ? topo.selectedPods : topo.selectedNodes;
  if (set.size === 0) return;

  const idPrefix = kind === 'pods' ? 'topo-pods' : 'topo-nodes';
  const btn = document.getElementById(`${idPrefix}-add-ctx`);
  if (btn) { btn.disabled = true; btn.textContent = '⏳ Building context…'; }

  try {
    let aggregated = `=== MULTI-CLUSTER ${kind.toUpperCase()} CONTEXT (${set.size} items) ===\n`;
    aggregated += `Generated: ${new Date().toISOString()}\n\n`;

    const itemsByCluster = new Map();
    for (const key of set) {
      const [clusterIdRaw, rest] = key.split('::');
      const clusterId = Number(clusterIdRaw);
      if (!itemsByCluster.has(clusterId)) itemsByCluster.set(clusterId, []);
      itemsByCluster.get(clusterId).push(rest);
    }

    for (const [clusterId, items] of itemsByCluster) {
      const cluster = topo.data.clusters.find(c => Number(c.id) === clusterId);
      const cName = cluster?.displayName || cluster?.name || `cluster-${clusterId}`;
      aggregated += `\n##############################\n# CLUSTER: ${cName} (id=${clusterId})\n##############################\n`;

      for (const itemRef of items) {
        if (kind === 'pods') {
          const [ns, name] = itemRef.split('/');
          aggregated += `\n--- POD ${ns}/${name} @ cluster ${cName} ---\n`;
          const desc = await podDetails(ns, name, 'describe', clusterId);
          if (desc.resp.ok) aggregated += `[DESCRIBE]\n${desc.data.describe || '(empty)'}\n`;
          const ev = await podDetails(ns, name, 'events', clusterId);
          if (ev.resp.ok) aggregated += `[EVENTS]\n${ev.data.events || '(none)'}\n`;
        } else {
          const name = itemRef;
          aggregated += `\n--- NODE ${name} @ cluster ${cName} ---\n`;
          const desc = await nodeDetails(name, 'describe', clusterId);
          if (desc.resp.ok) aggregated += `[DESCRIBE]\n${desc.data.describe || '(empty)'}\n`;
          const ev = await nodeDetails(name, 'events', clusterId);
          if (ev.resp.ok) aggregated += `[EVENTS]\n${ev.data.events || '(none)'}\n`;
        }
      }
    }

    addAttachment({
      name: `multi-${kind}-${set.size}.txt`,
      size: aggregated.length,
      type: 'text/plain',
      content: aggregated,
      _scanContext: true,
    });
    switchToTab('home');
  } catch (err) {
    alert('Failed to build multi-cluster context: ' + err.message);
  } finally {
    if (btn) { btn.disabled = false; btn.textContent = '➕ Add to context'; }
  }
}
