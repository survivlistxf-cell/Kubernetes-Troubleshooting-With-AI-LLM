import { state } from './state.js';
import { generateConversationId } from './utils.js';
import { loadChatHistoryIntoTab } from './history.js';

export function switchToTab(tabName) {
  document.querySelectorAll('.nav-item').forEach(btn => btn.classList.remove('active'));
  document.querySelectorAll('.tab-content').forEach(tab => tab.classList.remove('active'));

  const btn = document.querySelector(`.nav-item[data-tab="${tabName}"]`);
  const tab = document.getElementById(tabName);
  if (btn) btn.classList.add('active');
  if (tab) tab.classList.add('active');

  if (tabName === 'chat') loadChatHistoryIntoTab();
}

export function hideWelcomeHeader() {
  const welcomeHeader = document.querySelector('.welcome-header');
  if (welcomeHeader) welcomeHeader.style.display = 'none';
}

export function autoCollapseSidebar() {
  const sidebar = document.getElementById('sidebar');
  if (!sidebar) return;
  if (state.isFirstMessage) {
    sidebar.classList.add('collapsed');
    state.isFirstMessage = false;
  }
}

export function startNewConversation({ switchToHome = true } = {}) {
  const id = generateConversationId();  //aici se genereaza conversationId inainte sa fie primit de la backend dupa inserarea in db
  localStorage.setItem(state.conversationIdKey, id);

  const messagesArea = document.getElementById('messages');
  if (messagesArea) messagesArea.innerHTML = '';

  state.isFirstMessage = true;

  const welcomeHeader = document.querySelector('.welcome-header');
  if (welcomeHeader) welcomeHeader.style.display = '';

  if (switchToHome) switchToTab('home');
}

export function initNavigation() {
  const sidebar = document.getElementById('sidebar');
  const toggleBtn = document.getElementById('toggle-btn');

  toggleBtn?.addEventListener('click', () => sidebar?.classList.toggle('collapsed'));

  document.querySelectorAll('.nav-item').forEach(button => {
    button.addEventListener('click', () => {
      const tabName = button.dataset.tab;
      switchToTab(tabName);
    });
  });

  // New chat
  document.getElementById('new-chat-btn')?.addEventListener('click', (ev) => {
    ev.preventDefault();
    startNewConversation({ switchToHome: true });
  });
}
