import { state } from './state.js';
import { scanNodes, nodeDetails, scanNodesMulti } from './api.js';
import {
  escapeHtml, prettyJson, updateBulkUIComponent, syncCheckboxes, createHelperButton,
  buildSelectionSignature
} from './utils.js';
import { addAttachment, replaceOrAddScanAttachment } from './attachments.js';
import { switchToTab } from './navigation.js';
import { getSelectedClusterId } from './clusters.js';
import { saveNodesScan, readSavedNodesScan, clearNodesScan } from './session.js';

function getSelectedNodeLevels() {
  return Array.from(document.querySelectorAll('.node-detail-level:checked')).map(cb => cb.value);
}

function nodeKey(node) {
  return node._clusterId ? `${node._clusterId}::${node.name}` : node.name;
}

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

  function currentSignature() {
    return buildSelectionSignature(selectedNodes, getSelectedNodeLevels());
  }

  function isAlreadyAdded() {
    const last = state.lastNodeBulkAdd;
    if (!last) return false;
    if (last.signature !== currentSignature()) return false;
    return state.attachedFiles.some(f => f && f._scanContext && f.name === last.attachmentName);
  }

  function updateUI() {
    updateBulkUIComponent(
      { bulkOptions, selectedCountEl, bulkAddBtn, selectAllCheckbox },
      selectedNodes,
      state.lastScannedNodes || [],
      { alreadyAdded: isAlreadyAdded() }
    );
    syncRowSelectedClass();
  }

  function syncRowSelectedClass() {
    if (!nodesList) return;
    nodesList.querySelectorAll('.pod-item').forEach(row => {
      const cb = row.querySelector('.node-item-checkbox');
      const k = cb?.getAttribute('data-node-key');
      row.classList.toggle('is-selected', !!k && selectedNodes.has(k));
    });
  }

  document.querySelectorAll('.node-detail-level').forEach(cb => {
    cb.addEventListener('change', () => updateUI());
  });

  document.addEventListener('attachments:changed', () => updateUI());

  selectAllCheckbox?.addEventListener('change', (e) => {
    const checked = e.target.checked;
    selectedNodes.clear();
    if (checked) {
      (state.lastScannedNodes || []).forEach(n => {
        selectedNodes.add(nodeKey(n));
      });
    }
    // Update all node checkboxes in the list
    syncCheckboxes(nodesList, '.node-item-checkbox', 'data-node-key', selectedNodes);
    updateUI();
  });

  const isMaster = (node) => {
    const roles = (node.roles || '').toLowerCase();
    return roles.includes('control-plane') || roles.includes('master');
  };

  selectMasterBtn?.addEventListener('click', () => {
    selectedNodes.clear();
    (state.lastScannedNodes || []).forEach(n => {
      if (isMaster(n)) selectedNodes.add(nodeKey(n));
    });
    syncNodeCheckboxes();
    updateUI();
  });

  selectWorkerBtn?.addEventListener('click', () => {
    selectedNodes.clear();
    (state.lastScannedNodes || []).forEach(n => {
      if (!isMaster(n)) selectedNodes.add(nodeKey(n));
    });
    syncNodeCheckboxes();
    updateUI();
  });

  function syncNodeCheckboxes() {
    syncCheckboxes(nodesList, '.node-item-checkbox', 'data-node-key', selectedNodes);
  }

  // ---- Multi-cluster mode wiring ----
  const multiToggle = document.getElementById('nodes-multi-toggle');
  const multiPicker = document.getElementById('nodes-multi-picker');
  const multiList = document.getElementById('nodes-multi-cluster-list');
  const nodesClusterSelect = document.getElementById('nodes-cluster-select');

  function getSelectedMultiNodeClusterIds() {
    return Array.from(document.querySelectorAll('.nodes-multi-cluster-cb:checked')).map(cb => Number(cb.value));
  }

  function renderNodesMultiPicker() {
    if (!multiList) return;
    const sel = new Set(getSelectedMultiNodeClusterIds());
    multiList.innerHTML = (state.clusters || []).map(c => {
      const checked = sel.has(Number(c.id)) ? 'checked' : '';
      const cls = checked ? 'multi-cluster-pill checked' : 'multi-cluster-pill';
      const label = `${escapeHtml(c.displayName || c.name)}${(c.isDefault || c.default) ? ' ★' : ''}`;
      return `<label class="${cls}"><input type="checkbox" class="nodes-multi-cluster-cb" value="${c.id}" ${checked}> <span>${label}</span></label>`;
    }).join('');

    multiList.querySelectorAll('.nodes-multi-cluster-cb').forEach(cb => {
      cb.addEventListener('change', (ev) => {
        const checked = Array.from(multiList.querySelectorAll('.nodes-multi-cluster-cb:checked'));
        if (checked.length > 5) {
          ev.target.checked = false;
          alert('Maximum 5 clusters allowed.');
          return;
        }
        cb.closest('.multi-cluster-pill')?.classList.toggle('checked', cb.checked);
      });
    });
  }

  multiToggle?.addEventListener('change', () => {
    const on = !!multiToggle.checked;
    if (multiPicker) multiPicker.style.display = on ? '' : 'none';
    if (nodesClusterSelect) nodesClusterSelect.disabled = on;
    if (on) renderNodesMultiPicker();
  });
  document.addEventListener('clusters:refreshed', () => {
    if (multiToggle?.checked) renderNodesMultiPicker();
  });


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
    const isMulti = !!multiToggle?.checked;
    const multiIds = isMulti ? getSelectedMultiNodeClusterIds() : [];

    if (isMulti && multiIds.length === 0) {
      alert('Multi-cluster mode is on — select at least one cluster.');
      return;
    }

    scanNodesBtn.disabled = true;
    scanNodesBtn.style.opacity = '0.6';
    nodesScanLoading.style.display = 'block';
    nodesScanResults.style.display = 'none';
    nodesList.innerHTML = '';
    state.lastScannedNodes = [];
    state.activeClusterId = isMulti ? null : clusterId;
    selectedNodes.clear();
    if (bulkOptions) bulkOptions.style.display = 'none';
    if (selectAllCheckbox) selectAllCheckbox.checked = false;
    updateUI();

    if (isMulti) {
      const { resp, data } = await scanNodesMulti(multiIds);
      nodesScanLoading.style.display = 'none';
      nodesScanResults.style.display = 'block';
      if (!resp.ok) {
        nodesList.innerHTML = `<p style="text-align:center;color:#d32f2f;">Error: ${escapeHtml(data?.error || 'Multi-scan failed')}</p>`;
        scanNodesBtn.disabled = false;
        scanNodesBtn.style.opacity = '1';
        clearNodesScan();
        return;
      }
      const flat = [];
      for (const r of (data?.results || [])) {
        if (!r.success || !Array.isArray(r.nodes)) continue;
        const cName = r.clusterDisplayName || r.clusterName || `cluster-${r.clusterId}`;
        for (const n of r.nodes) flat.push({ ...n, _clusterId: r.clusterId, _clusterName: cName });
      }
      state.lastScannedNodes = flat;
      renderNodesList(flat);
      saveNodesScan({ nodes: flat, clusterId: null, ts: Date.now() });
      scanNodesBtn.disabled = false;
      scanNodesBtn.style.opacity = '1';
      return;
    }

    const { resp, data } = await scanNodes(clusterId);

    nodesScanLoading.style.display = 'none';
    nodesScanResults.style.display = 'block';

    if (!resp.ok) {
      const msg = data?.error || data?.message || 'Could not scan for nodes';
      nodesList.innerHTML = `<p style="text-align:center;color:#d32f2f;">Error: ${escapeHtml(msg)}</p>`;
      scanNodesBtn.disabled = false;
      scanNodesBtn.style.opacity = '1';
      clearNodesScan();
      return;
    }

    const nodes = data?.nodes || [];
    state.lastScannedNodes = nodes;
    renderNodesList(nodes);
    saveNodesScan({ nodes, clusterId, ts: Date.now() });

    scanNodesBtn.disabled = false;
    scanNodesBtn.style.opacity = '1';
  });

  function createNodeItemEl(n, key) {
    const div = document.createElement('div');
    div.className = 'pod-item';
    div.style.display = 'flex';
    div.style.alignItems = 'flex-start';
    div.style.gap = '1rem';

    div.innerHTML = `
      <input type="checkbox" class="node-item-checkbox" data-node-key="${escapeHtml(key)}" data-node-name="${escapeHtml(n.name)}"
             style="width: 18px; height: 18px; margin-top: 0.5rem; accent-color: var(--yellow-green); flex-shrink: 0;"
             ${selectedNodes.has(key) ? 'checked' : ''}>
      <div style="flex: 1;">
        <h4>🖥️ ${escapeHtml(n.name)}</h4>
        <div class="pod-info">
          <div class="pod-info-item"><span class="pod-info-label">Status:</span><span>${escapeHtml(n.status || 'N/A')}</span></div>
          <div class="pod-info-item"><span class="pod-info-label">Roles:</span><span>${escapeHtml(n.roles || 'N/A')}</span></div>
          <div class="pod-info-item"><span class="pod-info-label">Version:</span><span>${escapeHtml(n.version || 'N/A')}</span></div>
        </div>
        <button class="btn-details" type="button" data-node-name="${escapeHtml(n.name)}" data-node-cluster="${n._clusterId || ''}">🔎 Details</button>
      </div>
    `;

    const cb = div.querySelector('.node-item-checkbox');
    cb.addEventListener('change', (e) => {
      if (e.target.checked) selectedNodes.add(key);
      else selectedNodes.delete(key);
      updateUI();
    });
    div.addEventListener('click', (ev) => {
      if (ev.target.closest('button') || ev.target.closest('.node-item-checkbox')) return;
      cb.checked = !cb.checked;
      cb.dispatchEvent(new Event('change', { bubbles: true }));
    });
    return div;
  }

  function renderNodesList(nodes) {
    nodesList.innerHTML = '';
    if (bulkOptions && nodes.length > 0) bulkOptions.style.display = 'block';

    if (!nodes.length) {
      nodesList.innerHTML = '<p style="text-align:center;opacity:0.7;">No nodes found.</p>';
      return;
    }

    const isMulti = nodes.some(n => n._clusterId != null);

    if (isMulti) {
      // Group by cluster, maintain insertion order
      const clusterMap = new Map();
      for (const n of nodes) {
        const cid = String(n._clusterId);
        if (!clusterMap.has(cid)) {
          clusterMap.set(cid, { name: n._clusterName || `cluster-${cid}`, nodes: [] });
        }
        clusterMap.get(cid).nodes.push(n);
      }

      const grid = document.createElement('div');
      grid.className = 'multi-cluster-grid';
      grid.style.setProperty('--mc-cols', String(clusterMap.size));

      for (const [, { name, nodes: clusterNodes }] of clusterMap) {
        const col = document.createElement('div');
        col.className = 'multi-cluster-col';

        const header = document.createElement('div');
        header.className = 'multi-cluster-col-header';
        header.textContent = `☁️ ${name} (${clusterNodes.length})`;
        col.appendChild(header);

        for (const n of clusterNodes) {
          const key = nodeKey(n);
          col.appendChild(createNodeItemEl(n, key));
        }
        grid.appendChild(col);
      }
      nodesList.appendChild(grid);
    } else {
      nodes.forEach(n => {
        const key = nodeKey(n);
        nodesList.appendChild(createNodeItemEl(n, key));
      });
    }

    nodesList.querySelectorAll('button.btn-details').forEach(btn => {
      btn.addEventListener('click', async (ev) => {
        const name = ev.currentTarget?.dataset?.nodeName;
        const cidRaw = ev.currentTarget?.dataset?.nodeCluster;
        const nodeClusterId = cidRaw ? Number(cidRaw) : null;
        const node = (state.lastScannedNodes || []).find(x =>
          String(x.name) === String(name) &&
          (nodeClusterId ? Number(x._clusterId) === nodeClusterId : true)
        ) || { name };

        if (node._clusterId) state.activeClusterId = node._clusterId;

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

    updateUI();
  }

  // Restore previous scan results on init
  const savedNodes = readSavedNodesScan();
  if (savedNodes && Array.isArray(savedNodes.nodes) && savedNodes.nodes.length) {
    state.lastScannedNodes = savedNodes.nodes;
    state.activeClusterId = savedNodes.clusterId || null;
    nodesScanResults.style.display = 'block';
    renderNodesList(savedNodes.nodes);
  }



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

    const levels = getSelectedNodeLevels();
    const signature = currentSignature();

    bulkAddBtn.disabled = true;
    bulkAddBtn.textContent = '⌛ Fetching details...';

    const selectedNodeList = state.lastScannedNodes.filter(n => selectedNodes.has(nodeKey(n)));
    let aggregatedContext = `=== BULK NODE CONTEXT (${selectedNodeList.length} nodes) ===\n`;
    aggregatedContext += `Detail levels: ${levels.join(', ') || 'Summary only'}\n\n`;

    try {
      for (const node of selectedNodeList) {
        const clusterTag = node._clusterName ? ` @ cluster:${node._clusterName}` : '';
        aggregatedContext += `--- NODE: ${node.name}${clusterTag} ---\n`;
        aggregatedContext += `Status: ${node.status} | Roles: ${node.roles || 'N/A'} | Version: ${node.version}\n`;

        const nodeClusterId = node._clusterId || state.activeClusterId;
        if (levels.length > 0) {
          const detailsResults = await Promise.all(levels.map(async lvl => {
            const { resp, data } = await nodeDetails(node.name, lvl, nodeClusterId);
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

      const previousName = state.lastNodeBulkAdd?.attachmentName;
      const finalName = replaceOrAddScanAttachment({
        name: `bulk-nodes-${selectedNodeList.length}.txt`,
        size: aggregatedContext.length,
        type: 'text/plain',
        content: aggregatedContext,
        _scanContext: true,
      }, previousName);

      state.lastNodeBulkAdd = { signature, attachmentName: finalName };
      switchToTab('home');
    } catch (err) {
      console.error('Bulk fetch failed', err);
      alert('Failed to fetch some node details. Check console.');
    } finally {
      bulkAddBtn.innerHTML = `<span>➕</span> Add context for <strong>${selectedNodes.size}</strong> selected nodes`;
      updateUI();
    }
  });
}

async function loadNodeTabDetails(tab) {
  if (!state.selectedNodeForDetails) return;
  const node = state.selectedNodeForDetails;
  const name = node.name;

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

  const nodeClusterId = node._clusterId || state.activeClusterId;
  const { resp, data } = await nodeDetails(name, tab, nodeClusterId);
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
