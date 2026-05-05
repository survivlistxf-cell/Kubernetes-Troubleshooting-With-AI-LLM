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

export function initPreferences() {
  bind('pref-notifications-toggle', PREF_KEYS.notifications);
  bind('pref-autosave-toggle', PREF_KEYS.autosave);
}

export function getPreference(name) {
  const key = PREF_KEYS[name];
  if (!key) return null;
  return readBool(key, true);
}
