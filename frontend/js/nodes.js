import { state } from './state.js';
import { scanNodes, nodeDetails } from './api.js';
import { 
  escapeHtml, prettyJson, updateBulkUIComponent, syncCheckboxes, createHelperButton 
} from './utils.js';
import { addAttachment } from './attachments.js';
import { switchToTab } from './navigation.js';
import { getSelectedClusterId } from './clusters.js';

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
  const bulkOptions = document.getElementById('nodes-bulk-options');
  const selectAllCheckbox = document.getElementById('nodes-select-all');
  const selectedCountEl = document.getElementById('nodes-selected-count');
  const bulkAddBtn = document.getElementById('nodes-bulk-add-btn');
  const selectMasterBtn = document.getElementById('nodes-select-master');
  const selectWorkerBtn = document.getElementById('nodes-select-worker');

  let selectedNodes = new Set(); // Stores node names

  function updateUI() {
    updateBulkUIComponent(
      { bulkOptions, selectedCountEl, bulkAddBtn, selectAllCheckbox },
      selectedNodes,
      state.lastScannedNodes || []
    );
  }

  selectAllCheckbox?.addEventListener('change', (e) => {
    const checked = e.target.checked;
    selectedNodes.clear();
    if (checked) {
      (state.lastScannedNodes || []).forEach(n => {
        selectedNodes.add(n.name);
      });
    }
    // Update all node checkboxes in the list
    syncCheckboxes(nodesList, '.node-item-checkbox', 'data-node-name', selectedNodes);
    updateUI();
  });

  const isMaster = (node) => {
    const roles = (node.roles || '').toLowerCase();
    return roles.includes('control-plane') || roles.includes('master');
  };

  selectMasterBtn?.addEventListener('click', () => {
    selectedNodes.clear();
    (state.lastScannedNodes || []).forEach(n => {
      if (isMaster(n)) selectedNodes.add(n.name);
    });
    syncNodeCheckboxes();
    updateUI();
  });

  selectWorkerBtn?.addEventListener('click', () => {
    selectedNodes.clear();
    (state.lastScannedNodes || []).forEach(n => {
      if (!isMaster(n)) selectedNodes.add(n.name);
    });
    syncNodeCheckboxes();
    updateUI();
  });

  function syncNodeCheckboxes() {
    syncCheckboxes(nodesList, '.node-item-checkbox', 'data-node-name', selectedNodes);
  }


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
    const clusterSelect = document.getElementById('nodes-cluster-select');
    const clusterId = getSelectedClusterId(clusterSelect);

    scanNodesBtn.disabled = true;
    scanNodesBtn.style.opacity = '0.6';
    nodesScanLoading.style.display = 'block';
    nodesScanResults.style.display = 'none';
    nodesList.innerHTML = '';
    state.lastScannedNodes = [];
    state.activeClusterId = clusterId;
    selectedNodes.clear();
    if (bulkOptions) bulkOptions.style.display = 'none';
    if (selectAllCheckbox) selectAllCheckbox.checked = false;
    updateUI();

    const { resp, data } = await scanNodes(clusterId);

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

    if (bulkOptions && nodes.length > 0) bulkOptions.style.display = 'block';

    if (!nodes.length) {
      nodesList.innerHTML = '<p style="text-align:center;opacity:0.7;">No nodes found.</p>';
      scanNodesBtn.disabled = false;
      scanNodesBtn.style.opacity = '1';
      return;
    }

    nodes.forEach(n => {
      const div = document.createElement('div');
      div.className = 'pod-item';
      div.style.display = 'flex';
      div.style.alignItems = 'flex-start';
      div.style.gap = '1rem';

      div.innerHTML = `
        <input type="checkbox" class="node-item-checkbox" data-node-name="${escapeHtml(n.name)}" 
               style="width: 18px; height: 18px; margin-top: 0.5rem; accent-color: var(--yellow-green); flex-shrink: 0;"
               ${selectedNodes.has(n.name) ? 'checked' : ''}>
        <div style="flex: 1;">
          <h4>🖥️ ${escapeHtml(n.name)}</h4>
          <div class="pod-info">
            <div class="pod-info-item"><span class="pod-info-label">Status:</span><span>${escapeHtml(n.status || 'N/A')}</span></div>
            <div class="pod-info-item"><span class="pod-info-label">Roles:</span><span>${escapeHtml(n.roles || 'N/A')}</span></div>
            <div class="pod-info-item"><span class="pod-info-label">Version:</span><span>${escapeHtml(n.version || 'N/A')}</span></div>
          </div>
          <button class="btn-details" type="button" data-node-name="${escapeHtml(n.name)}">🔎 Details</button>
        </div>
      `;
      nodesList.appendChild(div);

      const cb = div.querySelector('.node-item-checkbox');
      cb.addEventListener('change', (e) => {
        if (e.target.checked) {
          selectedNodes.add(n.name);
        } else {
          selectedNodes.delete(n.name);
        }
        updateBulkUI();
      });
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



  document.getElementById('node-details-add-context')?.addEventListener('click', () => {
    if (!state.selectedNodeForDetails) return;
    const node = state.selectedNodeForDetails;
    const details = state.selectedNodeDetailsPayload;
    const name = node.name || details?.name || 'unknown-node';

    let text = `=== NODE DETAILS: ${name} ===\n`;
    text += `Name: ${name}\nStatus: ${node.status || 'N/A'}\nRoles: ${node.roles || 'N/A'}\nVersion: ${node.version || 'N/A'}\nInternal IP: ${node.internalIp || 'N/A'}\nExternal IP: ${node.externalIp || 'N/A'}\n`;

    if (details) {
      if (details.describe) text += `\n--- kubectl describe ---\n${details.describe}\n`;
      const nodeJson = details?.nodeJson || details?.node_json || null;
      if (nodeJson) text += `\n--- kubectl get node -o json ---\n${prettyJson(nodeJson)}\n`;
      if (details.events) text += `\n--- Events ---\n${details.events}\n`;
    }

    addAttachment({ name: `node-${name}.txt`, size: text.length, type: 'text/plain', content: text, _scanContext: true });
    closeNodeDetailsModal();
    switchToTab('home');
  });

  bulkAddBtn?.addEventListener('click', async () => {
    if (selectedNodes.size === 0) return;

    const levels = Array.from(document.querySelectorAll('.node-detail-level:checked')).map(cb => cb.value);
    
    bulkAddBtn.disabled = true;
    bulkAddBtn.textContent = '⌛ Fetching details...';

    const selectedNodeList = state.lastScannedNodes.filter(n => selectedNodes.has(n.name));
    let aggregatedContext = `=== BULK NODE CONTEXT (${selectedNodeList.length} nodes) ===\n`;
    aggregatedContext += `Detail levels: ${levels.join(', ') || 'Summary only'}\n\n`;

    try {
      for (const node of selectedNodeList) {
        aggregatedContext += `--- NODE: ${node.name} ---\n`;
        aggregatedContext += `Status: ${node.status} | Roles: ${node.roles || 'N/A'} | Version: ${node.version}\n`;
        
        if (levels.length > 0) {
          const detailsResults = await Promise.all(levels.map(async lvl => {
            const { resp, data } = await nodeDetails(node.name, lvl, state.activeClusterId);
            return { lvl, ok: resp.ok, data };
          }));

          detailsResults.forEach(res => {
            if (res.ok) {
              const payloadKey = res.lvl === 'json' ? 'nodeJson' : res.lvl;
              let content = res.data[payloadKey];
              if (res.lvl === 'json') content = prettyJson(content);
              aggregatedContext += `[${res.lvl.toUpperCase()}]\n${content}\n`;
            } else {
              aggregatedContext += `[${res.lvl.toUpperCase()}] ERROR: Failed to fetch\n`;
            }
          });
        }
        aggregatedContext += '\n';
      }

      addAttachment({ 
        name: `bulk-nodes-${selectedNodeList.length}.txt`, 
        size: aggregatedContext.length, 
        type: 'text/plain', 
        content: aggregatedContext, 
        _scanContext: true 
      });
      switchToTab('home');
    } catch (err) {
      console.error('Bulk fetch failed', err);
      alert('Failed to fetch some node details. Check console.');
    } finally {
      bulkAddBtn.disabled = false;
      updateUI();
      bulkAddBtn.innerHTML = `<span>➕</span> Add context for <strong>${selectedNodes.size}</strong> selected nodes`;
    }
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

  const { resp, data } = await nodeDetails(name, tab, state.activeClusterId);
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
