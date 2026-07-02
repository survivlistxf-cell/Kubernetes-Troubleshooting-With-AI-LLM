import { getRagMode, setRagMode } from './api.js';

const PREF_KEYS = {
  notifications: 'kubexplain.pref.notifications',
  autosave: 'kubexplain.pref.autosave',
};

function readBool(key, fallback) {
  const v = localStorage.getItem(key);
  if (v === null) return fallback;
  return v === '1' || v === 'true';
}

function writeBool(key, value) {
  localStorage.setItem(key, value ? '1' : '0');
}

function bind(toggleId, storageKey) {
  const toggle = document.getElementById(toggleId);
  if (!toggle) return;
  toggle.checked = readBool(storageKey, toggle.checked);
  toggle.addEventListener('change', () => writeBool(storageKey, toggle.checked));
}

// ---- RAG retrieval mode (server-side setting, not localStorage) -----------
//
// Unlike the toggles above, the RAG mode lives on the AI server (it affects every
// user), so it is read and written through the backend proxy (/api/ai/rag-mode).

const RAG_MODE_LABELS = {
  none: 'No RAG',
  static: 'Static RAG (air-gapped)',
  dynamic: 'Static + dynamic RAG',
};

let ragModeChangeBound = false;

async function loadRagMode(select, setFeedback) {
  try {
    const { resp, data } = await getRagMode();
    if (resp.ok && data && data.mode) {
      select.value = data.mode;
      select.dataset.lastMode = data.mode;
      select.disabled = false;
      setFeedback('', false);
      return;
    }

    if (resp.status === 401) {
      setFeedback('Sign in to load retrieval mode.', true);
      return;
    }

    setFeedback('AI server unreachable — retrieval mode unavailable.', true);
  } catch {
    setFeedback('AI server unreachable — retrieval mode unavailable.', true);
  }
}

function initRagMode() {
  const select = document.getElementById('rag-mode-select');
  const feedback = document.getElementById('rag-mode-feedback');
  if (!select) return;

  const setFeedback = (text, isError) => {
    if (!feedback) return;
    feedback.textContent = text || '';
    feedback.style.color = isError ? 'var(--danger-600, #dc2626)' : '';
  };

  // Load the current mode from the server; keep the control disabled until known.
  loadRagMode(select, setFeedback);

  if (!ragModeChangeBound) {
    select.addEventListener('change', async () => {
      const previous = select.dataset.lastMode || select.value;
      const mode = select.value;
      select.disabled = true;
      setFeedback('Applying…', false);
      try {
        const { resp, data } = await setRagMode(mode);
        if (resp.ok && data && data.mode) {
          select.dataset.lastMode = data.mode;
          setFeedback(`Active: ${RAG_MODE_LABELS[data.mode] || data.mode}.`, false);
        } else {
          select.value = previous;
          setFeedback((data && data.error) || 'Could not change retrieval mode.', true);
        }
      } catch {
        select.value = previous;
        setFeedback('Could not change retrieval mode.', true);
      } finally {
        select.disabled = false;
      }
    });
    ragModeChangeBound = true;
  }

  window.addEventListener('auth:login', () => {
    loadRagMode(select, setFeedback);
  }, { once: true });
}

export function initPreferences() {
  bind('pref-notifications-toggle', PREF_KEYS.notifications);
  bind('pref-autosave-toggle', PREF_KEYS.autosave);
  initRagMode();
}

export function getPreference(name) {
  const key = PREF_KEYS[name];
  if (!key) return null;
  return readBool(key, true);
}
