import { state } from './state.js';
import { scanPods, podDetails, getClusterNamespaces, scanPodsMulti } from './api.js';
import {
  escapeHtml, prettyJson, updateBulkUIComponent, syncCheckboxes, createHelperButton,
  buildSelectionSignature
} from './utils.js';
import { addAttachment, replaceOrAddScanAttachment } from './attachments.js';
import { switchToTab } from './navigation.js';
import { getSelectedClusterId } from './clusters.js';
import { savePodsScan, readSavedPodsScan, clearPodsScan } from './session.js';

const PREDEFINED_NAMESPACES = ['default', 'kube-system', 'kube-public', 'kube-node-lease'];
const NAMESPACE_STORAGE_KEY = 'kubexplain.lastNamespace';
const MULTI_NAMESPACE_STORAGE_KEY = 'kubexplain.lastPodNamespacesByCluster';
const MULTI_NAMESPACE_MODE_KEY = 'kubexplain.lastPodNamespaceMode';

let multiNamespaceSelections = loadMultiNamespaceSelections();
let multiNamespaceMode = localStorage.getItem(MULTI_NAMESPACE_MODE_KEY) === 'cluster' ? 'cluster' : 'shared';

function loadMultiNamespaceSelections() {
  try {
    const raw = localStorage.getItem(MULTI_NAMESPACE_STORAGE_KEY);
    const parsed = raw ? JSON.parse(raw) : {};
    return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {};
  } catch {
    return {};
  }
}

function saveMultiNamespaceSelections() {
  try {
    localStorage.setItem(MULTI_NAMESPACE_STORAGE_KEY, JSON.stringify(multiNamespaceSelections));
  } catch {}
}

function getMultiNamespaceValue(clusterId, fallback = 'default') {
  const value = multiNamespaceSelections[String(clusterId)];
  return (value != null && String(value).trim()) ? String(value).trim() : fallback;
}

function setMultiNamespaceValue(clusterId, value) {
  const key = String(clusterId);
  const nextValue = value != null ? String(value).trim() : '';
  if (nextValue) multiNamespaceSelections[key] = nextValue;
  else delete multiNamespaceSelections[key];
  saveMultiNamespaceSelections();
}

function setMultiNamespaceMode(mode) {
  multiNamespaceMode = mode === 'cluster' ? 'cluster' : 'shared';
  try {
    localStorage.setItem(MULTI_NAMESPACE_MODE_KEY, multiNamespaceMode);
  } catch {}
}

function getSelectedPodLevels() {
  return Array.from(document.querySelectorAll('.pod-detail-level:checked')).map(cb => cb.value);
}

// Stable selection key: includes cluster prefix only when in multi-cluster mode,
// so that single-cluster scans keep the legacy "ns/name" key format.
function podKey(pod) {
  return pod._clusterId
    ? `${pod._clusterId}::${pod.namespace}/${pod.name}`
    : `${pod.namespace}/${pod.name}`;
}

function populateNamespaceSelect(sel, namespaces, preferredValue) {
  if (!sel) return;
  const merged = Array.from(new Set([
    ...PREDEFINED_NAMESPACES,
    ...(namespaces || []),
    ...(preferredValue ? [preferredValue] : [])
  ])).sort((a, b) => {
    // keep `default` first, then alphabetical
    if (a === 'default') return -1;
    if (b === 'default') return 1;
    return a.localeCompare(b);
  });

  const previous = preferredValue ?? sel.value;
  sel.innerHTML = '';
  merged.forEach(ns => {
    const opt = document.createElement('option');
    opt.value = ns;
    opt.textContent = ns;
    sel.appendChild(opt);
  });

  if (merged.includes(previous)) {
    sel.value = previous;
  } else {
    sel.value = 'default';
  }
}

async function refreshNamespacesForCluster(clusterId, selectEl, preferredValue) {
  if (!selectEl) return;
  if (!clusterId) {
    populateNamespaceSelect(selectEl, [], preferredValue);
    return;
  }
  try {
    const { resp, data } = await getClusterNamespaces(clusterId);
    const list = (resp.ok && Array.isArray(data?.namespaces)) ? data.namespaces : [];
    populateNamespaceSelect(selectEl, list, preferredValue);
  } catch {
    populateNamespaceSelect(selectEl, [], preferredValue);
  }
}

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
  const namespaceSelect = document.getElementById('namespace-select');
  const namespaceGroup = namespaceSelect?.closest('.filter-group');
  const multiNamespaceToggle = document.getElementById('pods-multi-shared-namespace');

  let selectedPods = new Set(); // Stores pod names (or unique identifiers like ns/name)

  function currentSignature() {
    const base = buildSelectionSignature(selectedPods, getSelectedPodLevels());
    if (!multiToggle?.checked) return base;

    const namespaceSig = getSelectedMultiClusterIds()
      .map(clusterId => {
        const cluster = (state.clusters || []).find(c => Number(c.id) === Number(clusterId));
        const fallbackNamespace = cluster?.defaultNamespace || 'default';
        return `${clusterId}=${getMultiNamespaceValue(clusterId, fallbackNamespace)}`;
      })
      .sort()
      .join('|');

    return `${base}::${namespaceSig}`;
  }

  function isAlreadyAdded() {
    const last = state.lastPodBulkAdd;
    if (!last) return false;
    if (last.signature !== currentSignature()) return false;
    // The previous attachment must still exist in the draft
    return state.attachedFiles.some(f => f && f._scanContext && f.name === last.attachmentName);
  }

  function updateUI() {
    updateBulkUIComponent(
      { bulkOptions, selectedCountEl, bulkAddBtn, selectAllCheckbox },
      selectedPods,
      state.lastScannedPods || [],
      { alreadyAdded: isAlreadyAdded() }
    );
    syncRowSelectedClass();
  }

  function syncRowSelectedClass() {
    if (!podsList) return;
    podsList.querySelectorAll('.pod-item').forEach(row => {
      const cb = row.querySelector('.pod-item-checkbox');
      const id = cb?.getAttribute('data-pod-id');
      row.classList.toggle('is-selected', !!id && selectedPods.has(id));
    });
  }

  // Detail-level checkboxes affect button enable/disable too
  document.querySelectorAll('.pod-detail-level').forEach(cb => {
    cb.addEventListener('change', () => updateUI());
  });

  // If the user removes the attachment pill from chat, re-enable the button
  document.addEventListener('attachments:changed', () => updateUI());

  selectAllCheckbox?.addEventListener('change', (e) => {
    const checked = e.target.checked;
    selectedPods.clear();
    if (checked) {
      (state.lastScannedPods || []).forEach(p => {
        selectedPods.add(podKey(p));
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

  // Namespace select wiring: persist last value + auto-fetch when cluster changes
  const podsClusterSelect = document.getElementById('pods-cluster-select');

  const savedNs = localStorage.getItem(NAMESPACE_STORAGE_KEY);
  populateNamespaceSelect(namespaceSelect, [], savedNs || 'default');

  namespaceSelect?.addEventListener('change', () => {
    if (namespaceSelect.value) localStorage.setItem(NAMESPACE_STORAGE_KEY, namespaceSelect.value);
  });

  podsClusterSelect?.addEventListener('change', () => {
    const cid = getSelectedClusterId(podsClusterSelect);
    refreshNamespacesForCluster(cid, namespaceSelect, namespaceSelect?.value || 'default');
  });

  // Try to fetch namespaces once clusters become available
  setTimeout(() => {
    const cid = getSelectedClusterId(podsClusterSelect);
    if (cid) refreshNamespacesForCluster(cid, namespaceSelect, namespaceSelect?.value || 'default');
  }, 600);

  function syncScannerMode() {
    const on = !!multiToggle?.checked;
    const shared = multiNamespaceMode === 'shared';
    if (namespaceGroup) namespaceGroup.style.display = (!on || shared) ? '' : 'none';
    if (multiPicker) multiPicker.style.display = on ? '' : 'none';
    if (podsClusterSelect) podsClusterSelect.disabled = on;
    if (multiPicker) multiPicker.classList.toggle('pods-multi-picker--shared', on && shared);
    if (multiPicker) multiPicker.classList.toggle('pods-multi-picker--cluster', on && !shared);
    if (multiNamespaceToggle) multiNamespaceToggle.checked = shared;
    if (on) renderPodsMultiPicker();
  }

  // ---- Multi-cluster mode wiring ----
  const multiToggle = document.getElementById('pods-multi-toggle');
  const multiPicker = document.getElementById('pods-multi-picker');
  const multiList = document.getElementById('pods-multi-cluster-list');

  multiNamespaceToggle?.addEventListener('change', () => {
    setMultiNamespaceMode(multiNamespaceToggle.checked ? 'shared' : 'cluster');
    syncScannerMode();
  });

  function renderPodsMultiPicker() {
    if (!multiList) return;
    const sel = new Set(getSelectedMultiClusterIds());
    multiList.innerHTML = (state.clusters || []).map(c => {
      const clusterId = Number(c.id);
      const checked = sel.has(clusterId);
      const defaultNamespace = getMultiNamespaceValue(clusterId, c.defaultNamespace || 'default');
      const rowClass = checked ? 'pods-multi-cluster-row is-selected' : 'pods-multi-cluster-row';
      const pillClass = checked ? 'multi-cluster-pill pods-multi-cluster-pill checked' : 'multi-cluster-pill pods-multi-cluster-pill';
      const label = `${escapeHtml(c.displayName || c.name)}${(c.isDefault || c.default) ? ' ★' : ''}`;
      return `
        <div class="${rowClass}">
          <label class="${pillClass}">
            <input type="checkbox" class="pods-multi-cluster-cb" value="${c.id}" ${checked ? 'checked' : ''}>
            <span>${label}</span>
          </label>
          <div class="pods-multi-cluster-namespace">
            <label class="filter-label" for="pods-multi-namespace-${c.id}">Namespace</label>
            <select id="pods-multi-namespace-${c.id}" class="namespace-selector form-control pods-multi-namespace-select" data-cluster-id="${c.id}" ${checked ? '' : 'disabled'}>
              <option value="${escapeHtml(defaultNamespace)}">${escapeHtml(defaultNamespace)}</option>
            </select>
          </div>
        </div>`;
    }).join('');

    multiList.querySelectorAll('.pods-multi-cluster-cb').forEach(cb => {
      cb.addEventListener('change', (ev) => {
        const checked = Array.from(multiList.querySelectorAll('.pods-multi-cluster-cb:checked'));
        if (checked.length > 5) {
          ev.target.checked = false;
          alert('Maximum 5 clusters allowed.');
          return;
        }
        const row = cb.closest('.pods-multi-cluster-row');
        row?.classList.toggle('is-selected', cb.checked);
        cb.closest('.multi-cluster-pill')?.classList.toggle('checked', cb.checked);
        const selectEl = row?.querySelector('.pods-multi-namespace-select');
        if (selectEl) selectEl.disabled = !cb.checked || multiNamespaceMode === 'shared';
      });
    });

    multiList.querySelectorAll('.pods-multi-namespace-select').forEach(selectEl => {
      const clusterId = Number(selectEl.dataset.clusterId);
      const cluster = (state.clusters || []).find(c => Number(c.id) === clusterId);
      const preferredValue = getMultiNamespaceValue(clusterId, selectEl.value || cluster?.defaultNamespace || 'default');
      selectEl.value = preferredValue;
      selectEl.disabled = multiNamespaceMode === 'shared' || !selectEl.closest('.pods-multi-cluster-row')?.querySelector('.pods-multi-cluster-cb')?.checked;
      selectEl.addEventListener('change', () => {
        setMultiNamespaceValue(clusterId, selectEl.value);
      });
      refreshNamespacesForCluster(clusterId, selectEl, preferredValue);
    });
  }

  function getSelectedMultiClusterIds() {
    return Array.from(document.querySelectorAll('.pods-multi-cluster-cb:checked')).map(cb => Number(cb.value));
  }

  multiToggle?.addEventListener('change', syncScannerMode);
  // re-render picker when clusters refresh
  document.addEventListener('clusters:refreshed', () => {
    if (multiToggle?.checked) renderPodsMultiPicker();
  });

  syncScannerMode();

  scanBtn?.addEventListener('click', async () => {
    const namespace = (namespaceSelect?.value || 'default').trim() || 'default';
    const clusterSelect = document.getElementById('pods-cluster-select');
    const clusterId = getSelectedClusterId(clusterSelect);
    const isMulti = !!multiToggle?.checked;
    const multiIds = isMulti ? getSelectedMultiClusterIds() : [];

    if (isMulti && multiIds.length === 0) {
      alert('Multi-cluster mode is on — select at least one cluster.');
      return;
    }

    const namespaceMap = {};
    if (isMulti && multiNamespaceMode === 'cluster') {
      for (const clusterIdValue of multiIds) {
        const selectEl = document.querySelector(`.pods-multi-namespace-select[data-cluster-id="${clusterIdValue}"]`);
        const cluster = (state.clusters || []).find(c => Number(c.id) === Number(clusterIdValue));
        const chosenNamespace = (selectEl?.value || getMultiNamespaceValue(clusterIdValue, cluster?.defaultNamespace || 'default')).trim() || 'default';
        namespaceMap[String(clusterIdValue)] = chosenNamespace;
        setMultiNamespaceValue(clusterIdValue, chosenNamespace);
      }
    }

    scanBtn.disabled = true;
    scanBtn.style.opacity = '0.6';
    scanLoading.style.display = 'block';
    scanResults.style.display = 'none';
    podsList.innerHTML = '';
    state.lastScannedPods = [];
    state.activeClusterId = isMulti ? null : clusterId;
    selectedPods.clear();
    if (bulkOptions) bulkOptions.style.display = 'none';
    if (selectAllCheckbox) selectAllCheckbox.checked = false;
    if (dynamicHelpersEl) dynamicHelpersEl.innerHTML = '';
    updateUI();

    if (isMulti) {
      const { resp, data } = await scanPodsMulti(multiIds, multiNamespaceMode === 'cluster' ? namespaceMap : namespace);
      scanLoading.style.display = 'none';
      scanResults.style.display = 'block';
      if (!resp.ok) {
        podsList.innerHTML = `<p style="text-align: center; color: #d32f2f;">Error: ${escapeHtml(data?.error || 'Multi-scan failed')}</p>`;
        if (bulkOptions) bulkOptions.style.display = 'none';
        clearPodsScan();
        scanBtn.disabled = false;
        scanBtn.style.opacity = '1';
        return;
      }
      const flat = [];
      for (const r of (data?.results || [])) {
        if (!r.success || !Array.isArray(r.pods)) continue;
        const cName = r.clusterDisplayName || r.clusterName || `cluster-${r.clusterId}`;
        for (const p of r.pods) {
          flat.push({ ...p, _clusterId: r.clusterId, _clusterName: cName });
        }
      }
      state.lastScannedPods = flat;
      renderPodsList(flat);
      savePodsScan({
        pods: flat,
        clusterId: null,
        namespace: multiNamespaceMode === 'cluster' ? namespaceMap : namespace,
        namespaceMap: multiNamespaceMode === 'cluster' ? namespaceMap : null,
        namespaceMode: multiNamespaceMode,
        ts: Date.now()
      });
      scanBtn.disabled = false;
      scanBtn.style.opacity = '1';
      return;
    }

    const { resp, data } = await scanPods(namespace, clusterId);

    scanLoading.style.display = 'none';
    scanResults.style.display = 'block';

    if (resp.ok) {
      const pods = data?.pods || [];
      state.lastScannedPods = pods;
      renderPodsList(pods);

      // Persist for reload
      savePodsScan({ pods, clusterId, namespace, ts: Date.now() });
    } else {
      const message = data?.error || data?.message || 'Could not scan for pods';
      podsList.innerHTML = `<p style="text-align: center; color: #d32f2f;">Error: ${escapeHtml(message)}</p>`;
      if (bulkOptions) bulkOptions.style.display = 'none';
      clearPodsScan();
    }

    scanBtn.disabled = false;
    scanBtn.style.opacity = '1';
  });

  function createPodItemEl(pod, podId) {
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
        <button class="btn-details" type="button" data-pod-namespace="${escapeHtml(pod.namespace)}" data-pod-name="${escapeHtml(pod.name)}" data-pod-cluster="${pod._clusterId || ''}">🔎 Details</button>
      </div>
    `;

    const cb = podDiv.querySelector('.pod-item-checkbox');
    cb.addEventListener('change', (e) => {
      if (e.target.checked) selectedPods.add(podId);
      else selectedPods.delete(podId);
      updateUI();
    });
    podDiv.addEventListener('click', (ev) => {
      if (ev.target.closest('button') || ev.target.closest('.pod-item-checkbox')) return;
      cb.checked = !cb.checked;
      cb.dispatchEvent(new Event('change', { bubbles: true }));
    });
    return podDiv;
  }

  function renderPodsList(pods) {
    podsList.innerHTML = '';
    if (bulkOptions && pods.length > 0) {
      bulkOptions.style.display = 'block';
      generateDynamicHelpers(pods);
    }

    if (!pods.length) {
      podsList.innerHTML = '<p style="text-align: center; opacity: 0.7;">No Kubernetes pods found on this system.</p>';
      return;
    }

    const isMulti = pods.some(p => p._clusterId != null);

    if (isMulti) {
      // Group by cluster, maintain insertion order
      const clusterMap = new Map();
      for (const pod of pods) {
        const cid = String(pod._clusterId);
        if (!clusterMap.has(cid)) {
          clusterMap.set(cid, { name: pod._clusterName || `cluster-${cid}`, pods: [] });
        }
        clusterMap.get(cid).pods.push(pod);
      }

      const grid = document.createElement('div');
      grid.className = 'multi-cluster-grid';
      grid.style.setProperty('--mc-cols', String(clusterMap.size));

      for (const [, { name, pods: clusterPods }] of clusterMap) {
        const col = document.createElement('div');
        col.className = 'multi-cluster-col';

        const header = document.createElement('div');
        header.className = 'multi-cluster-col-header';
        header.textContent = `☁️ ${name} (${clusterPods.length})`;
        col.appendChild(header);

        for (const pod of clusterPods) {
          const podId = `${pod._clusterId}::${pod.namespace}/${pod.name}`;
          col.appendChild(createPodItemEl(pod, podId));
        }
        grid.appendChild(col);
      }
      podsList.appendChild(grid);
    } else {
      pods.forEach(pod => {
        const podId = `${pod.namespace}/${pod.name}`;
        podsList.appendChild(createPodItemEl(pod, podId));
      });
    }

    podsList.querySelectorAll('button.btn-details').forEach(btn => {
      btn.addEventListener('click', async (ev) => {
        const ns = ev.currentTarget?.dataset?.podNamespace;
        const name = ev.currentTarget?.dataset?.podName;
        const cidRaw = ev.currentTarget?.dataset?.podCluster;
        const podClusterId = cidRaw ? Number(cidRaw) : null;
        const pod = (state.lastScannedPods || []).find(p =>
          String(p.name) === String(name) &&
          String(p.namespace) === String(ns) &&
          (podClusterId ? Number(p._clusterId) === podClusterId : true)
        ) || { namespace: ns, name };

        // For details modal, point to the right cluster on a per-pod basis
        if (pod._clusterId) state.activeClusterId = pod._clusterId;

        state.selectedPodForDetails = { ...pod };
        state.selectedPodDetailsPayload = {};

        const titleEl = document.getElementById('pod-details-title');
        if (titleEl) titleEl.textContent = `Pod details: ${ns}/${name}`;

        renderPodDetailsMeta(pod, null);
        setActivePodTab('describe');

        document.getElementById('pod-details-describe').textContent = 'Loading details...';
        document.getElementById('pod-details-json').textContent = '';
        document.getElementById('pod-details-events').textContent = '';
        document.getElementById('pod-details-logs').textContent = '';

        openPodDetailsModal();
        await loadPodTabDetails('describe');
      });
    });

    updateUI();
  }

  // Restore previous scan results on init
  const saved = readSavedPodsScan();
  if (saved && Array.isArray(saved.pods) && saved.pods.length) {
    state.lastScannedPods = saved.pods;
    state.activeClusterId = saved.clusterId || null;
    if (saved.namespaceMode === 'cluster' || saved.namespaceMode === 'shared') {
      setMultiNamespaceMode(saved.namespaceMode);
    }
    const savedNamespaceMap = saved.namespaceMap && typeof saved.namespaceMap === 'object' && !Array.isArray(saved.namespaceMap)
      ? saved.namespaceMap
      : (saved.namespace && typeof saved.namespace === 'object' && !Array.isArray(saved.namespace) ? saved.namespace : null);
    if (savedNamespaceMap) {
      multiNamespaceSelections = { ...multiNamespaceSelections, ...savedNamespaceMap };
      saveMultiNamespaceSelections();
    }
    if (saved.namespace && namespaceSelect) {
      if (typeof saved.namespace === 'string') {
        // Make sure the saved namespace is in the select options
        if (![...namespaceSelect.options].some(o => o.value === saved.namespace)) {
          const opt = document.createElement('option');
          opt.value = saved.namespace;
          opt.textContent = saved.namespace;
          namespaceSelect.appendChild(opt);
        }
        namespaceSelect.value = saved.namespace;
      }
    }
    scanResults.style.display = 'block';
    renderPodsList(saved.pods);
  }

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

    const levels = getSelectedPodLevels();
    const signature = currentSignature();

    bulkAddBtn.disabled = true;
    bulkAddBtn.textContent = '⌛ Fetching details...';

    const selectedPodList = state.lastScannedPods.filter(p => selectedPods.has(podKey(p)));
    let aggregatedContext = `=== BULK POD CONTEXT (${selectedPodList.length} pods) ===\n`;
    aggregatedContext += `Detail levels: ${levels.join(', ') || 'Summary only'}\n\n`;

    try {
      for (const pod of selectedPodList) {
        const clusterTag = pod._clusterName ? ` @ cluster:${pod._clusterName}` : '';
        aggregatedContext += `--- POD: ${pod.namespace}/${pod.name}${clusterTag} ---\n`;
        aggregatedContext += `Status: ${pod.status} | Node: ${pod.node || 'N/A'} | Ready: ${pod.ready} | Restarts: ${pod.restarts}\n`;

        const podClusterId = pod._clusterId || state.activeClusterId;
        if (levels.length > 0) {
          const detailsResults = await Promise.all(levels.map(async lvl => {
            const { resp, data } = await podDetails(pod.namespace, pod.name, lvl, podClusterId);
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

      const previousName = state.lastPodBulkAdd?.attachmentName;
      const finalName = replaceOrAddScanAttachment({
        name: `bulk-pods-${selectedPodList.length}.txt`,
        size: aggregatedContext.length,
        type: 'text/plain',
        content: aggregatedContext,
        _scanContext: true,
      }, previousName);

      state.lastPodBulkAdd = { signature, attachmentName: finalName };
      switchToTab('home');
    } catch (err) {
      console.error('Bulk fetch failed', err);
      alert('Failed to fetch some pod details. Check console.');
    } finally {
      bulkAddBtn.innerHTML = `<span>➕</span> Add context for <strong>${selectedPods.size}</strong> selected pods`;
      updateUI();
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
            selectedPods.add(podKey(p));
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
          if (p.node === node) selectedPods.add(podKey(p));
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

  const podClusterId = pod._clusterId || state.activeClusterId;
  const { resp, data } = await podDetails(ns, name, tab, podClusterId);
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
