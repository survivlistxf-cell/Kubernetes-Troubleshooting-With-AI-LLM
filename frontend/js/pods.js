import { state } from './state.js';
import { scanPods, podDetails } from './api.js';
import { 
  escapeHtml, prettyJson, updateBulkUIComponent, syncCheckboxes, createHelperButton 
} from './utils.js';
import { addAttachment } from './attachments.js';
import { switchToTab } from './navigation.js';

function setActivePodTab(tab) {
  const buttons = Array.from(document.querySelectorAll('#pod-details-modal .pod-tab-btn'));
  const panels = Array.from(document.querySelectorAll('#pod-details-modal .pod-tab-panel'));
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

function openPodDetailsModal() {
  document.getElementById('pod-details-modal')?.style && (document.getElementById('pod-details-modal').style.display = 'flex');
  document.getElementById('pod-details-modal-overlay')?.style && (document.getElementById('pod-details-modal-overlay').style.display = 'block');
}

function closePodDetailsModal() {
  const modal = document.getElementById('pod-details-modal');
  const overlay = document.getElementById('pod-details-modal-overlay');
  if (modal) modal.style.display = 'none';
  if (overlay) overlay.style.display = 'none';
  state.selectedPodForDetails = null;
  state.selectedPodDetailsPayload = null;
  document.getElementById('pod-details-describe').textContent = '';
  document.getElementById('pod-details-json').textContent = '';
  document.getElementById('pod-details-events').textContent = '';
  document.getElementById('pod-details-logs').textContent = '';
  document.getElementById('pod-details-meta').innerHTML = '';
  setActivePodTab('describe');
}

function renderPodDetailsMeta(pod, details) {
  const meta = document.getElementById('pod-details-meta');
  if (!meta) return;
  const items = [
    { k: 'Namespace', v: pod?.namespace || details?.namespace || 'default' },
    { k: 'Name', v: pod?.name || details?.name || 'unknown' },
    { k: 'Status', v: pod?.status || details?.status || 'N/A' },
    { k: 'Node', v: pod?.node || details?.node || 'N/A' },
    { k: 'Ready', v: pod?.ready || 'N/A' },
    { k: 'Restarts', v: pod?.restarts ?? 'N/A' },
    { k: 'Age', v: pod?.age || 'N/A' }
  ];
  meta.innerHTML = items
    .filter(i => i.v != null && String(i.v).trim() !== '')
    .map(i => `<span class="pod-details-badge"><strong>${escapeHtml(i.k)}:</strong> ${escapeHtml(i.v)}</span>`)
    .join('');
}

export function initPodsScanner() {
  const scanBtn = document.getElementById('scan-btn');
  const scanResults = document.getElementById('scan-results');
  const scanLoading = document.getElementById('scan-loading');
  const podsList = document.getElementById('pods-list');
  const bulkOptions = document.getElementById('pods-bulk-options');
  const selectAllCheckbox = document.getElementById('pods-select-all');
  const selectedCountEl = document.getElementById('pods-selected-count');
  const bulkAddBtn = document.getElementById('pods-bulk-add-btn');
  const dynamicHelpersEl = document.getElementById('pods-dynamic-helpers');

  let selectedPods = new Set(); // Stores pod names (or unique identifiers like ns/name)

  function updateUI() {
    updateBulkUIComponent(
      { bulkOptions, selectedCountEl, bulkAddBtn, selectAllCheckbox },
      selectedPods,
      state.lastScannedPods || []
    );
  }

  selectAllCheckbox?.addEventListener('change', (e) => {
    const checked = e.target.checked;
    selectedPods.clear();
    if (checked) {
      (state.lastScannedPods || []).forEach(p => {
        selectedPods.add(`${p.namespace}/${p.name}`);
      });
    }
    // Update all pod checkboxes in the list
    syncCheckboxes(podsList, '.pod-item-checkbox', 'data-pod-id', selectedPods);
    updateUI();
  });

  // modal buttons
  document.getElementById('pod-details-close')?.addEventListener('click', closePodDetailsModal);
  document.getElementById('pod-details-cancel')?.addEventListener('click', closePodDetailsModal);
  document.getElementById('pod-details-modal-overlay')?.addEventListener('click', closePodDetailsModal);
  document.addEventListener('keydown', (ev) => {
    if (ev.key !== 'Escape') return;
    const open = document.getElementById('pod-details-modal')?.style?.display && document.getElementById('pod-details-modal').style.display !== 'none';
    if (open) closePodDetailsModal();
  });

  Array.from(document.querySelectorAll('#pod-details-modal .pod-tab-btn')).forEach(btn => {
    btn.addEventListener('click', async () => {
      const tab = btn.dataset.tab;
      setActivePodTab(tab);
      await loadPodTabDetails(tab);
    });
  });

  scanBtn?.addEventListener('click', async () => {
    const namespaceInput = document.getElementById('namespace-input');
    const namespace = (namespaceInput?.value || 'default').trim() || 'default';

    scanBtn.disabled = true;
    scanBtn.style.opacity = '0.6';
    scanLoading.style.display = 'block';
    scanResults.style.display = 'none';
    podsList.innerHTML = '';
    state.lastScannedPods = [];
    selectedPods.clear();
    if (bulkOptions) bulkOptions.style.display = 'none';
    if (selectAllCheckbox) selectAllCheckbox.checked = false;
    if (dynamicHelpersEl) dynamicHelpersEl.innerHTML = '';
    updateUI();

    const { resp, data } = await scanPods(namespace);

    scanLoading.style.display = 'none';
    scanResults.style.display = 'block';

    if (resp.ok) {
      const pods = data?.pods || [];
      state.lastScannedPods = pods;

      if (bulkOptions && pods.length > 0) {
        bulkOptions.style.display = 'block';
        generateDynamicHelpers(pods);
      }

      if (pods.length) {
        pods.forEach(pod => {
          const podId = `${pod.namespace}/${pod.name}`;
          const podDiv = document.createElement('div');
          podDiv.className = 'pod-item';
          podDiv.style.display = 'flex';
          podDiv.style.alignItems = 'flex-start';
          podDiv.style.gap = '1rem';
          
          podDiv.innerHTML = `
            <input type="checkbox" class="pod-item-checkbox" data-pod-id="${escapeHtml(podId)}" 
                   style="width: 18px; height: 18px; margin-top: 0.5rem; accent-color: var(--yellow-green); flex-shrink: 0;"
                   ${selectedPods.has(podId) ? 'checked' : ''}>
            <div style="flex: 1;">
              <h4>📦 ${escapeHtml(pod.name)}</h4>
              <div class="pod-info">
                <div class="pod-info-item"><span class="pod-info-label">Namespace:</span><span>${escapeHtml(pod.namespace)}</span></div>
                <div class="pod-info-item"><span class="pod-info-label">Status:</span><span>${escapeHtml(pod.status)}</span></div>
                <div class="pod-info-item"><span class="pod-info-label">Node:</span><span>${escapeHtml(pod.node || 'N/A')}</span></div>
                <div class="pod-info-item"><span class="pod-info-label">Containers:</span><span>${escapeHtml(pod.containers)}</span></div>
              </div>
              <button class="btn-details" type="button" data-pod-namespace="${escapeHtml(pod.namespace)}" data-pod-name="${escapeHtml(pod.name)}">🔎 Details</button>
            </div>
          `;
          podsList.appendChild(podDiv);

          const cb = podDiv.querySelector('.pod-item-checkbox');
          cb.addEventListener('change', (e) => {
            if (e.target.checked) {
              selectedPods.add(podId);
            } else {
              selectedPods.delete(podId);
            }
            updateBulkUI();
          });
        });

        podsList.querySelectorAll('button.btn-details').forEach(btn => {
          btn.addEventListener('click', async (ev) => {
            const ns = ev.currentTarget?.dataset?.podNamespace;
            const name = ev.currentTarget?.dataset?.podName;
            const pod = (state.lastScannedPods || []).find(p => String(p.name) === String(name) && String(p.namespace) === String(ns)) || { namespace: ns, name };

            state.selectedPodForDetails = { ...pod };
            state.selectedPodDetailsPayload = {};

            const titleEl = document.getElementById('pod-details-title');
            if (titleEl) titleEl.textContent = `Pod details: ${ns}/${name}`;

            renderPodDetailsMeta(pod, null);
            setActivePodTab('describe');

            // Clear all tab contents
            document.getElementById('pod-details-describe').textContent = 'Loading details...';
            document.getElementById('pod-details-json').textContent = '';
            document.getElementById('pod-details-events').textContent = '';
            document.getElementById('pod-details-logs').textContent = '';

            openPodDetailsModal();
            await loadPodTabDetails('describe');
          });
        });
      } else {
        podsList.innerHTML = '<p style="text-align: center; opacity: 0.7;">No Kubernetes pods found on this system.</p>';
      }
    } else {
      const message = data?.error || data?.message || 'Could not scan for pods';
      podsList.innerHTML = `<p style="text-align: center; color: #d32f2f;">Error: ${escapeHtml(message)}</p>`;
      if (bulkOptions) bulkOptions.style.display = 'none';
    }

    scanBtn.disabled = false;
    scanBtn.style.opacity = '1';
  });

  document.getElementById('pod-details-add-context')?.addEventListener('click', () => {
    if (!state.selectedPodForDetails) return;
    const pod = state.selectedPodForDetails;
    const details = state.selectedPodDetailsPayload;
    const ns = pod?.namespace || details?.namespace || 'default';
    const name = pod?.name || details?.name || 'unknown-pod';

    let text = `=== POD DETAILS: ${ns}/${name} ===\n`;
    text += `Namespace: ${ns}\nName: ${name}\nStatus: ${pod?.status || 'N/A'}\nNode: ${pod?.node || 'N/A'}\nReady: ${pod?.ready || 'N/A'}\nRestarts: ${pod?.restarts ?? 'N/A'}\nAge: ${pod?.age || 'N/A'}\nContainers: ${pod?.containers || 'N/A'}\n`;

    if (details) {
      if (details.describe) text += `\n--- kubectl describe ---\n${details.describe}\n`;
      const podJson = details?.podJson || details?.pod_json || null;
      if (podJson) text += `\n--- kubectl get pod -o json ---\n${prettyJson(podJson)}\n`;
      if (details.events) text += `\n--- Events ---\n${details.events}\n`;
      if (details.logs) text += `\n--- Logs (tail 200) ---\n${details.logs}\n`;
    }

    addAttachment({ name: `pod-${name}.txt`, size: text.length, type: 'text/plain', content: text, _scanContext: true });
    closePodDetailsModal();
    switchToTab('home');
  });



  bulkAddBtn?.addEventListener('click', async () => {
    if (selectedPods.size === 0) return;

    const levels = Array.from(document.querySelectorAll('.pod-detail-level:checked')).map(cb => cb.value);
    
    bulkAddBtn.disabled = true;
    bulkAddBtn.textContent = '⌛ Fetching details...';

    const selectedPodList = state.lastScannedPods.filter(p => selectedPods.has(`${p.namespace}/${p.name}`));
    let aggregatedContext = `=== BULK POD CONTEXT (${selectedPodList.length} pods) ===\n`;
    aggregatedContext += `Detail levels: ${levels.join(', ') || 'Summary only'}\n\n`;

    try {
      for (const pod of selectedPodList) {
        aggregatedContext += `--- POD: ${pod.namespace}/${pod.name} ---\n`;
        aggregatedContext += `Status: ${pod.status} | Node: ${pod.node || 'N/A'} | Ready: ${pod.ready} | Restarts: ${pod.restarts}\n`;
        
        if (levels.length > 0) {
          const detailsResults = await Promise.all(levels.map(async lvl => {
            const { resp, data } = await podDetails(pod.namespace, pod.name, lvl);
            return { lvl, ok: resp.ok, data };
          }));

          detailsResults.forEach(res => {
            if (res.ok) {
              const payloadKey = res.lvl === 'json' ? 'podJson' : res.lvl;
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
        name: `bulk-pods-${selectedPodList.length}.txt`, 
        size: aggregatedContext.length, 
        type: 'text/plain', 
        content: aggregatedContext, 
        _scanContext: true 
      });
      switchToTab('home');
    } catch (err) {
      console.error('Bulk fetch failed', err);
      alert('Failed to fetch some pod details. Check console.');
    } finally {
      bulkAddBtn.disabled = false;
      updateUI();
      bulkAddBtn.innerHTML = `<span>➕</span> Add context for <strong>${selectedPods.size}</strong> selected pods`;
    }
  });

  function generateDynamicHelpers(allPods) {
    if (!dynamicHelpersEl) return;
    dynamicHelpersEl.innerHTML = '';

    const deployments = new Set();
    const nodes = new Set();

    allPods.forEach(p => {
      const parts = p.name.split('-');
      if (parts.length >= 3) {
        deployments.add(parts.slice(0, -2).join('-'));
      } else if (parts.length > 1) {
        deployments.add(parts[0]);
      } else {
        deployments.add(p.name);
      }
      if (p.node) nodes.add(p.node);
    });

    const sortedDep = Array.from(deployments).sort();
    const sortedNodes = Array.from(nodes).sort();

    sortedDep.forEach(dep => {
      const btn = createHelperButton(dep, '📦', () => {
        selectedPods.clear();
        allPods.forEach(p => {
          if (p.name === dep || p.name.startsWith(dep + '-')) {
            selectedPods.add(`${p.namespace}/${p.name}`);
          }
        });
        syncPodCheckboxes();
        updateUI();
      });
      dynamicHelpersEl.appendChild(btn);
    });

    if (sortedDep.length && sortedNodes.length) {
      const sep = document.createElement('span');
      sep.className = 'bulk-toolbar-separator';
      sep.textContent = '|';
      dynamicHelpersEl.appendChild(sep);
    }

    sortedNodes.forEach(node => {
      const btn = createHelperButton(node, '🖥️', () => {
        selectedPods.clear();
        allPods.forEach(p => {
          if (p.node === node) selectedPods.add(`${p.namespace}/${p.name}`);
        });
        syncPodCheckboxes();
        updateUI();
      });
      dynamicHelpersEl.appendChild(btn);
    });
  }

  function syncPodCheckboxes() {
    syncCheckboxes(podsList, '.pod-item-checkbox', 'data-pod-id', selectedPods);
  }
}

async function loadPodTabDetails(tab) {
  if (!state.selectedPodForDetails) return;
  const pod = state.selectedPodForDetails;
  const ns = pod.namespace;
  const name = pod.name;

  const mapping = {
    'describe': 'describe',
    'json': 'podJson',
    'events': 'events',
    'logs': 'logs'
  };

  const payloadKey = mapping[tab];
  if (!payloadKey) return;

  if (state.selectedPodDetailsPayload && state.selectedPodDetailsPayload[payloadKey]) {
    renderPodTabContent(tab);
    return;
  }

  const el = document.getElementById(`pod-details-${tab}`);
  if (el) el.textContent = 'Loading...';

  const { resp, data } = await podDetails(ns, name, tab);
  if (resp.ok) {
    if (!state.selectedPodDetailsPayload) state.selectedPodDetailsPayload = {};
    state.selectedPodDetailsPayload[payloadKey] = data[payloadKey];
    renderPodTabContent(tab);
    renderPodDetailsMeta(pod, state.selectedPodDetailsPayload);
  } else {
    if (el) el.textContent = `Failed to load pod details: ${data?.error || data?.message || resp.statusText}`;
  }
}

function renderPodTabContent(tab) {
  const pod = state.selectedPodForDetails;
  if (!pod) return;
  const details = state.selectedPodDetailsPayload;
  if (!details) return;

  const ns = pod.namespace;
  const name = pod.name;

  if (tab === 'describe') {
    const describe = details.describe || '(no describe output)';
    document.getElementById('pod-details-describe').textContent = `# kubectl describe pod ${name} -n ${ns}\n${describe}`;
  } else if (tab === 'json') {
    const podJson = details.podJson || details.pod_json || null;
    document.getElementById('pod-details-json').textContent = `# kubectl get pod ${name} -n ${ns} -o json\n${prettyJson(podJson)}`;
  } else if (tab === 'events') {
    const events = details.events || '(no events output)';
    document.getElementById('pod-details-events').textContent = `# kubectl get events -n ${ns} --field-selector involvedObject.name=${name}\n${events}`;
  } else if (tab === 'logs') {
    const logs = details.logs || '(no logs output)';
    document.getElementById('pod-details-logs').textContent = `# kubectl logs ${name} -n ${ns} --tail=200\n${logs}`;
  }
}
