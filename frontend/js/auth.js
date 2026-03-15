import { escapeHtml } from './utils.js';
import { loadChatHistoryIntoTab } from './history.js';

// Auth modal helpers from your existing UI
export function openLoginModal() {
  const modal = document.getElementById('auth-modal');
  const overlay = document.getElementById('auth-modal-overlay');
  const login = document.getElementById('login-form-container');
  const reg = document.getElementById('register-form-container');

  if (modal) modal.style.display = 'flex';
  if (overlay) overlay.style.display = 'block';
  if (login) login.style.display = 'block';
  if (reg) reg.style.display = 'none';
}

export function closeAuthModal() {
  const modal = document.getElementById('auth-modal');
  const overlay = document.getElementById('auth-modal-overlay');
  if (modal) modal.style.display = 'none';
  if (overlay) overlay.style.display = 'none';
}

export function switchToRegister(ev) {
  ev?.preventDefault?.();
  document.getElementById('login-form-container')?.style && (document.getElementById('login-form-container').style.display = 'none');
  document.getElementById('register-form-container')?.style && (document.getElementById('register-form-container').style.display = 'block');
}

export function switchToLogin(ev) {
  ev?.preventDefault?.();
  document.getElementById('register-form-container')?.style && (document.getElementById('register-form-container').style.display = 'none');
  document.getElementById('login-form-container')?.style && (document.getElementById('login-form-container').style.display = 'block');
}

function updateUIAfterLogin(username) {
  document.getElementById('auth-btn').style.display = 'none';
  document.getElementById('logout-btn').style.display = 'block';
  document.getElementById('user-info').style.display = 'block';
  document.getElementById('user-info').textContent = `👤 ${username}`;
}

function updateUIAfterLogout() {
  document.getElementById('auth-btn').style.display = 'block';
  document.getElementById('logout-btn').style.display = 'none';
  document.getElementById('user-info').style.display = 'none';
  document.getElementById('user-info').textContent = '';
}

async function handleLoginSubmit(ev) {
  ev.preventDefault();
  const email = document.getElementById('login-email')?.value;
  const password = document.getElementById('login-password')?.value;

  try {
    const resp = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password })
    });
    const data = await resp.json().catch(() => null);
    if (!resp.ok) {
      alert(data?.message || data?.error || 'Login failed');
      return;
    }

    // store session (match backend flat structure: userId, username, email, token)
    if (data?.token) localStorage.setItem('authToken', data.token);
    if (data?.username) localStorage.setItem('currentUser', JSON.stringify({ username: data.username, email: data.email }));
    if (data?.userId != null) localStorage.setItem('userId', String(data.userId));

    updateUIAfterLogin(data?.username || data?.email || 'User');
    closeAuthModal();
    loadChatHistoryIntoTab();
  } catch (e) {
    console.error('Login error:', e);
    alert('Login failed: ' + (e?.message || e));
  }
}

async function handleRegisterSubmit(ev) {
  ev.preventDefault();
  const username = document.getElementById('register-username')?.value;
  const email = document.getElementById('register-email')?.value;
  const password = document.getElementById('register-password')?.value;
  const confirm = document.getElementById('register-confirm')?.value;

  if (password !== confirm) {
    alert('Passwords do not match');
    return;
  }

  try {
    const resp = await fetch('/api/auth/register', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, email, password })
    });
    const data = await resp.json().catch(() => null);
    if (!resp.ok) {
      alert(data?.message || data?.error || 'Register failed');
      return;
    }

    alert('Account created! Please login.');
    switchToLogin();
  } catch (e) {
    alert('Register failed: ' + (e?.message || e));
  }
}

export function initAuth() {
  console.log('Initializing Auth...');
  // Existing session
  try {
    const current = localStorage.getItem('currentUser');
    const userId = localStorage.getItem('userId');
    console.log('Current stored session:', { userId, current });

    let user = null;
    user = JSON.parse(current);

    if (user || userId) {
      console.log('Session found! Updating UI...');
      updateUIAfterLogin(user?.username || user?.name || user?.email || 'User');
      // Auto-load history on page load if logged in
      loadChatHistoryIntoTab();
    } else {
      console.log('No session found.');
      updateUIAfterLogout();
    }
  } catch (err) {
    console.error('Error during initAuth:', err);
    updateUIAfterLogout();
  }

  document.getElementById('login-form')?.addEventListener('submit', handleLoginSubmit);
  document.getElementById('register-form')?.addEventListener('submit', handleRegisterSubmit);

  document.getElementById('logout-btn')?.addEventListener('click', () => {
    localStorage.removeItem('authToken');
    localStorage.removeItem('currentUser');
    localStorage.removeItem('userId');
    updateUIAfterLogout();
    location.reload();
  });

  // Expose for inline onclick in index.html
  window.openLoginModal = openLoginModal;
  window.closeAuthModal = closeAuthModal;
  window.switchToRegister = switchToRegister;
  window.switchToLogin = switchToLogin;
}
