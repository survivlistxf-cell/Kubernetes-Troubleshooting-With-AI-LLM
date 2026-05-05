const STORAGE_KEY = 'kubexplain.theme';

function applyTheme(theme) {
  document.body.classList.toggle('theme-dark', theme === 'dark');
}

export function initTheme() {
  const saved = localStorage.getItem(STORAGE_KEY);
  const prefersDark = window.matchMedia?.('(prefers-color-scheme: dark)').matches;
  const theme = saved || (prefersDark ? 'dark' : 'light');
  applyTheme(theme);

  const toggle = document.getElementById('theme-dark-toggle');
  if (toggle) {
    toggle.checked = theme === 'dark';
    toggle.addEventListener('change', () => {
      const next = toggle.checked ? 'dark' : 'light';
      applyTheme(next);
      localStorage.setItem(STORAGE_KEY, next);
    });
  }
}
