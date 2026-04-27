import { state } from './state.js';
import { getClusters, addCluster, deleteCluster, testCluster, getClusterNamespaces } from './api.js';
import { escapeHtml } from './utils.js';

/**
 * Fetch clusters from backend and update state + all dropdowns.
 */
export async function refreshClusters() {
  try {
    const { resp, data } = await getClusters();
    if (resp.ok && Array.isArray(data)) {
      state.clusters = data;
    } else {
      state.clusters = [];
    }
  } catch {
    state.clusters = [];
  }
  renderClusterDropdowns();
  renderClusterManager();
}

/**
 * Populate all cluster <select> dropdowns across scanners.
 */
function renderClusterDropdowns() {
  const selects = document.querySelectorAll('.cluster-selector');
  selects.forEach(sel => {
    const currentVal = sel.value;
    sel.innerHTML = '';

    // Default option
    const defaultOpt = document.createElement('option');
    defaultOpt.value = '';
    defaultOpt.textContent = state.clusters.length ? '— Select cluster —' : '— No clusters configured —';
    sel.appendChild(defaultOpt);

    state.clusters.forEach(c => {
      const opt = document.createElement('option');
      opt.value = String(c.id);
      opt.textContent = `${c.displayName || c.name}${c.isDefault ? ' ★' : ''}`;
      sel.appendChild(opt);
    });

    // Restore previous selection, or select default cluster
    if (currentVal && [...sel.options].some(o => o.value === currentVal)) {
      sel.value = currentVal;
    } else {
      const defaultCluster = state.clusters.find(c => c.isDefault || c.default);
      if (defaultCluster) {
        sel.value = String(defaultCluster.id);
      }
    }
  });
}

/**
 * Get the currently selected clusterId from a scanner's dropdown.
 */
export function getSelectedClusterId(selectElement) {
  if (!selectElement) return null;
  const val = selectElement.value;
  return val ? Number(val) : null;
}

/**
 * Render the Cluster Manager UI in the Settings tab.
 */
function renderClusterManager() {
  const container = document.getElementById('cluster-manager-list');
  if (!container) return;

  if (state.clusters.length === 0) {
    container.innerHTML = `
      <div class="cluster-empty-state">
        <p>📋 No clusters configured yet</p>
        <p style="opacity:0.7; font-size:0.9rem;">Add your first cluster using the form above to start scanning pods and nodes across multiple environments.</p>
      </div>`;
    return;
  }

  container.innerHTML = state.clusters.map(c => `
    <div class="cluster-card ${(c.isDefault || c.default) ? 'cluster-card--default' : ''}" data-cluster-id="${c.id}">
      <div class="cluster-card-header">
        <div class="cluster-card-title">
          <span class="cluster-card-icon">${(c.isDefault || c.default) ? '⭐' : '☁️'}</span>
          <strong>${escapeHtml(c.displayName || c.name)}</strong>
          ${(c.isDefault || c.default) ? '<span class="cluster-badge cluster-badge--default">DEFAULT</span>' : ''}
        </div>
        <div class="cluster-card-actions">
          <button class="cluster-action-btn cluster-test-btn" data-id="${c.id}" title="Test connectivity">🔌 Test</button>
          <button class="cluster-action-btn cluster-ns-btn" data-id="${c.id}" title="List namespaces">📂 NS</button>
          <button class="cluster-action-btn cluster-delete-btn" data-id="${c.id}" title="Delete cluster">🗑️</button>
        </div>
      </div>
      <div class="cluster-card-meta">
        <span>📛 <strong>Name:</strong> ${escapeHtml(c.name)}</span>
        <span>📁 <strong>Default NS:</strong> ${escapeHtml(c.defaultNamespace || 'default')}</span>
        ${c.contextName ? `<span>🔗 <strong>Context:</strong> ${escapeHtml(c.contextName)}</span>` : ''}
      </div>
      <div class="cluster-card-status" id="cluster-status-${c.id}"></div>
    </div>
  `).join('');

  // Bind action buttons
  container.querySelectorAll('.cluster-test-btn').forEach(btn => {
    btn.addEventListener('click', () => handleTestCluster(Number(btn.dataset.id)));
  });

  container.querySelectorAll('.cluster-ns-btn').forEach(btn => {
    btn.addEventListener('click', () => handleListNamespaces(Number(btn.dataset.id)));
  });

  container.querySelectorAll('.cluster-delete-btn').forEach(btn => {
    btn.addEventListener('click', () => handleDeleteCluster(Number(btn.dataset.id)));
  });
}

async function handleTestCluster(id) {
  const statusEl = document.getElementById(`cluster-status-${id}`);
  if (statusEl) {
    statusEl.innerHTML = '<span class="cluster-status-testing">⏳ Testing connectivity...</span>';
  }

  try {
    const { data } = await testCluster(id);
    if (data?.connected) {
      if (statusEl) statusEl.innerHTML = '<span class="cluster-status-ok">✅ Connected successfully</span>';
    } else {
      const error = data?.error || 'Connection failed';
      if (statusEl) statusEl.innerHTML = `<span class="cluster-status-error">❌ ${escapeHtml(String(error).substring(0, 200))}</span>`;
    }
  } catch (err) {
    if (statusEl) statusEl.innerHTML = `<span class="cluster-status-error">❌ ${escapeHtml(err.message)}</span>`;
  }
}

async function handleListNamespaces(id) {
  const statusEl = document.getElementById(`cluster-status-${id}`);
  if (statusEl) statusEl.innerHTML = '<span class="cluster-status-testing">⏳ Fetching namespaces...</span>';

  try {
    const { data } = await getClusterNamespaces(id);
    const nsList = data?.namespaces || [];
    if (nsList.length > 0) {
      if (statusEl) statusEl.innerHTML = `<span class="cluster-status-ok">📂 Namespaces (${nsList.length}): <code>${nsList.map(n => escapeHtml(n)).join('</code>, <code>')}</code></span>`;
    } else {
      if (statusEl) statusEl.innerHTML = `<span class="cluster-status-error">No namespaces found or connection failed</span>`;
    }
  } catch (err) {
    if (statusEl) statusEl.innerHTML = `<span class="cluster-status-error">❌ ${escapeHtml(err.message)}</span>`;
  }
}

async function handleDeleteCluster(id) {
  const cluster = state.clusters.find(c => c.id === id);
  const name = cluster?.displayName || cluster?.name || 'this cluster';
  if (!confirm(`Are you sure you want to delete "${name}"?`)) return;

  try {
    await deleteCluster(id);
    await refreshClusters();
  } catch (err) {
    alert('Failed to delete cluster: ' + err.message);
  }
}

/**
 * Initialize the Cluster Manager: form submission + initial load.
 */
export function initClusterManager() {
  const form = document.getElementById('add-cluster-form');
  if (!form) return;

  form.addEventListener('submit', async (ev) => {
    ev.preventDefault();
    const submitBtn = form.querySelector('button[type="submit"]');
    const fileInput = document.getElementById('cluster-kubeconfig-file');
    const nameInput = document.getElementById('cluster-name-input');
    const displayInput = document.getElementById('cluster-display-input');
    const contextInput = document.getElementById('cluster-context-input');
    const nsInput = document.getElementById('cluster-ns-input');
    const defaultCheckbox = document.getElementById('cluster-is-default');
    const feedback = document.getElementById('add-cluster-feedback');

    if (!fileInput?.files?.length) {
      if (feedback) feedback.innerHTML = '<span class="cluster-status-error">Please select a kubeconfig file</span>';
      return;
    }

    const formData = new FormData();
    formData.append('kubeconfigFile', fileInput.files[0]);
    formData.append('name', nameInput?.value?.trim() || '');
    formData.append('displayName', displayInput?.value?.trim() || '');
    formData.append('contextName', contextInput?.value?.trim() || '');
    formData.append('defaultNamespace', nsInput?.value?.trim() || 'default');
    formData.append('isDefault', defaultCheckbox?.checked ? 'true' : 'false');

    if (submitBtn) { submitBtn.disabled = true; submitBtn.textContent = '⌛ Adding...'; }
    if (feedback) feedback.innerHTML = '';

    try {
      const { resp, data } = await addCluster(formData);
      if (resp.ok) {
        if (feedback) feedback.innerHTML = '<span class="cluster-status-ok">✅ Cluster added successfully!</span>';
        form.reset();
        await refreshClusters();
      } else {
        const msg = data?.error || 'Failed to add cluster';
        if (feedback) feedback.innerHTML = `<span class="cluster-status-error">❌ ${escapeHtml(msg)}</span>`;
      }
    } catch (err) {
      if (feedback) feedback.innerHTML = `<span class="cluster-status-error">❌ ${escapeHtml(err.message)}</span>`;
    } finally {
      if (submitBtn) { submitBtn.disabled = false; submitBtn.textContent = '➕ Add Cluster'; }
    }
  });

  // Initial load
  refreshClusters();
}
