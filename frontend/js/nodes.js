import { state } from './state.js';
import { escapeHtml, prettyJson } from './utils.js';
import { scanNodes, nodeDetails } from './api.js';

function setActiveNodeTab(tab) {
  const buttons = Array.from(document.querySelectorAll('#node-details-modal .pod-tab-btn'));
  const panels = Array.from(document.querySelectorAll('#node-details-modal .pod-tab-panel'));
  const t = String(tab || 'describe');

  buttons.forEach(b => {
    const isActive = b.dataset.tab === t;
    b.classList.toggle('active', isActive);
    b.setAttribute('aria-selected', isActive ? 'true' : 'false');
  });
  panels.forEach(p => {
    const isActive = p.dataset.panel === t;
    p.classList.toggle('active', isActive);
    p.hidden = !isActive;
  });
}

function openNodeDetailsModal() {
  const modal = document.getElementById('node-details-modal');
  const overlay = document.getElementById('node-details-modal-overlay');
  if (modal) modal.style.display = 'flex';
  if (overlay) overlay.style.display = 'block';
}

function closeNodeDetailsModal() {
  const modal = document.getElementById('node-details-modal');
  const overlay = document.getElementById('node-details-modal-overlay');
  if (modal) modal.style.display = 'none';
  if (overlay) overlay.style.display = 'none';
  state.selectedNodeForDetails = null;
  state.selectedNodeDetailsPayload = null;
  document.getElementById('node-details-describe').textContent = '';
  document.getElementById('node-details-json').textContent = '';
  document.getElementById('node-details-events').textContent = '';
  document.getElementById('node-details-meta').innerHTML = '';
  setActiveNodeTab('describe');
}

function renderNodeDetailsMeta(node, details) {
  const meta = document.getElementById('node-details-meta');
  if (!meta) return;
  const items = [
    { k: 'Name', v: node?.name || details?.name || 'unknown' },
    { k: 'Status', v: node?.status || details?.status || 'N/A' },
    { k: 'Roles', v: node?.roles || 'N/A' },
    { k: 'Version', v: node?.version || 'N/A' },
    { k: 'Internal IP', v: node?.internalIp || 'N/A' },
    { k: 'External IP', v: node?.externalIp || 'N/A' }
  ];
  meta.innerHTML = items
    .filter(i => i.v != null && String(i.v).trim() !== '')
    .map(i => `<span class="pod-details-badge"><strong>${escapeHtml(i.k)}:</strong> ${escapeHtml(i.v)}</span>`)
    .join('');
}

export function initNodesScanner() {
  const scanNodesBtn = document.getElementById('scan-nodes-btn');
  const nodesScanResults = document.getElementById('nodes-scan-results');
  const nodesScanLoading = document.getElementById('nodes-scan-loading');
  const nodesList = document.getElementById('nodes-list');

  document.getElementById('node-details-close')?.addEventListener('click', closeNodeDetailsModal);
  document.getElementById('node-details-cancel')?.addEventListener('click', closeNodeDetailsModal);
  document.getElementById('node-details-modal-overlay')?.addEventListener('click', closeNodeDetailsModal);

  Array.from(document.querySelectorAll('#node-details-modal .pod-tab-btn')).forEach(btn => {
    btn.addEventListener('click', async () => {
      const tab = btn.dataset.tab;
      setActiveNodeTab(tab);
      await loadNodeTabDetails(tab);
    });
  });

  scanNodesBtn?.addEventListener('click', async () => {
    scanNodesBtn.disabled = true;
    scanNodesBtn.style.opacity = '0.6';
    nodesScanLoading.style.display = 'block';
    nodesScanResults.style.display = 'none';
    nodesList.innerHTML = '';
    state.lastScannedNodes = [];

    const { resp, data } = await scanNodes();

    nodesScanLoading.style.display = 'none';
    nodesScanResults.style.display = 'block';

    if (!resp.ok) {
      const msg = data?.error || data?.message || 'Could not scan for nodes';
      nodesList.innerHTML = `<p style="text-align:center;color:#d32f2f;">Error: ${escapeHtml(msg)}</p>`;
      scanNodesBtn.disabled = false;
      scanNodesBtn.style.opacity = '1';
      return;
    }

    const nodes = data?.nodes || [];
    state.lastScannedNodes = nodes;

    if (!nodes.length) {
      nodesList.innerHTML = '<p style="text-align:center;opacity:0.7;">No nodes found.</p>';
      scanNodesBtn.disabled = false;
      scanNodesBtn.style.opacity = '1';
      return;
    }

    nodes.forEach(n => {
      const div = document.createElement('div');
      div.className = 'pod-item';
      div.innerHTML = `
        <h4>🖥️ ${escapeHtml(n.name)}</h4>
        <div class="pod-info">
          <div class="pod-info-item"><span class="pod-info-label">Status:</span><span>${escapeHtml(n.status || 'N/A')}</span></div>
          <div class="pod-info-item"><span class="pod-info-label">Roles:</span><span>${escapeHtml(n.roles || 'N/A')}</span></div>
          <div class="pod-info-item"><span class="pod-info-label">Version:</span><span>${escapeHtml(n.version || 'N/A')}</span></div>
        </div>
        <button class="btn-details" type="button" data-node-name="${escapeHtml(n.name)}">🔎 Details</button>
      `;
      nodesList.appendChild(div);
    });

    nodesList.querySelectorAll('button.btn-details').forEach(btn => {
      btn.addEventListener('click', async (ev) => {
        const name = ev.currentTarget?.dataset?.nodeName;
        const node = (state.lastScannedNodes || []).find(x => String(x.name) === String(name)) || { name };

        state.selectedNodeForDetails = { ...node };
        state.selectedNodeDetailsPayload = {};

        document.getElementById('node-details-title').textContent = `Node details: ${name}`;
        renderNodeDetailsMeta(node, null);
        setActiveNodeTab('describe');

        document.getElementById('node-details-describe').textContent = 'Loading details...';
        document.getElementById('node-details-json').textContent = '';
        document.getElementById('node-details-events').textContent = '';

        openNodeDetailsModal();
        await loadNodeTabDetails('describe');
      });
    });

    scanNodesBtn.disabled = false;
    scanNodesBtn.style.opacity = '1';
  });
}

async function loadNodeTabDetails(tab) {
  if (!state.selectedNodeForDetails) return;
  const name = state.selectedNodeForDetails.name;

  const mapping = {
    'describe': 'describe',
    'json': 'nodeJson',
    'events': 'events'
  };

  const payloadKey = mapping[tab];
  if (!payloadKey) return;

  if (state.selectedNodeDetailsPayload && state.selectedNodeDetailsPayload[payloadKey]) {
    renderNodeTabContent(tab);
    return;
  }

  const el = document.getElementById(`node-details-${tab === 'json' ? 'json' : tab}`);
  if (el) el.textContent = 'Loading...';

  const { resp, data } = await nodeDetails(name, tab);
  if (resp.ok) {
    if (!state.selectedNodeDetailsPayload) state.selectedNodeDetailsPayload = {};
    state.selectedNodeDetailsPayload[payloadKey] = data[payloadKey];
    renderNodeTabContent(tab);
    renderNodeDetailsMeta(state.selectedNodeForDetails, state.selectedNodeDetailsPayload);
  } else {
    if (el) el.textContent = `Failed to load node details: ${data?.error || data?.message || resp.statusText}`;
  }
}

function renderNodeTabContent(tab) {
  const node = state.selectedNodeForDetails;
  if (!node) return;
  const details = state.selectedNodeDetailsPayload;
  if (!details) return;

  const name = node.name;

  if (tab === 'describe') {
    const describe = details.describe || '(no describe output)';
    document.getElementById('node-details-describe').textContent = `# kubectl describe node ${name}\n${describe}`;
  } else if (tab === 'json') {
    const nodeJson = details.nodeJson || details.node_json || null;
    document.getElementById('node-details-json').textContent = `# kubectl get node ${name} -o json\n${prettyJson(nodeJson)}`;
  } else if (tab === 'events') {
    const events = details.events || '(no events output)';
    document.getElementById('node-details-events').textContent = `# kubectl get events --field-selector involvedObject.name=${name}\n${events}`;
  }
}
