/**
 * UI glue for the redesigned frontend:
 * - Suggestion cards on the Home hero -> populate prompt and submit
 * - Settings sub-nav -> switch between panes
 * - Chat history search -> live filter rows
 * - Header-side "header-new-chat" mirror (if present)
 */

function autoresizePromptInput(promptInput) {
  if (!promptInput) return;
  promptInput.style.height = 'auto';
  promptInput.style.height = Math.min(promptInput.scrollHeight, 150) + 'px';
}

function initSuggestionCards() {
  const cards = document.querySelectorAll('.suggestion-card[data-suggest]');
  if (!cards.length) return;

  cards.forEach(card => {
    card.addEventListener('click', () => {
      const text = card.getAttribute('data-suggest') || '';
      const promptInput = document.getElementById('prompt-input');
      const promptForm = document.getElementById('prompt-form');
      if (!promptInput || !promptForm) return;

      promptInput.value = text;
      autoresizePromptInput(promptInput);
      promptInput.focus();
      promptForm.dispatchEvent(new Event('submit'));
    });
  });
}

function initSettingsSubnav() {
  const items = document.querySelectorAll('.settings-subnav-item');
  const panes = document.querySelectorAll('.settings-pane[data-section]');
  if (!items.length || !panes.length) return;

  function activate(section) {
    items.forEach(it => it.classList.toggle('active', it.dataset.section === section));
    panes.forEach(p => {
      const match = p.dataset.section === section;
      if (match) p.removeAttribute('hidden');
      else p.setAttribute('hidden', '');
    });
  }

  items.forEach(it => {
    it.addEventListener('click', () => activate(it.dataset.section));
  });
}

function initHistorySearch() {
  const search = document.getElementById('history-search');
  const container = document.getElementById('chat-history-content');
  if (!search || !container) return;

  search.addEventListener('input', () => {
    const q = search.value.trim().toLowerCase();
    const rows = container.querySelectorAll('.conversation-item');
    let visible = 0;
    rows.forEach(row => {
      const text = row.textContent.toLowerCase();
      const match = !q || text.includes(q);
      row.style.display = match ? '' : 'none';
      if (match) visible++;
    });
    // toggle a synthetic empty-state line when filter has no matches
    let emptyMsg = container.querySelector('[data-search-empty]');
    if (q && visible === 0 && rows.length > 0) {
      if (!emptyMsg) {
        emptyMsg = document.createElement('p');
        emptyMsg.className = 'empty-state';
        emptyMsg.dataset.searchEmpty = '1';
        emptyMsg.textContent = `No conversations match “${q}”.`;
        container.appendChild(emptyMsg);
      } else {
        emptyMsg.textContent = `No conversations match “${q}”.`;
      }
    } else if (emptyMsg) {
      emptyMsg.remove();
    }
  });
}

export function initUI() {
  initSuggestionCards();
  initSettingsSubnav();
  initHistorySearch();
}
