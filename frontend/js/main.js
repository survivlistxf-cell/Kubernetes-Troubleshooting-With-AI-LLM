import { initNavigation } from './navigation.js';
import { initModals } from './modals.js';
import { initAttachments } from './attachments.js';
import { initResourcePicker } from './resourcePicker.js';
import { initChat } from './chat.js';
import { initPodsScanner } from './pods.js';
import { initNodesScanner } from './nodes.js';
import { initAuth } from './auth.js';

document.addEventListener('DOMContentLoaded', () => {
  initAuth();
  initNavigation();
  initModals();
  initAttachments();
  initResourcePicker();
  initChat();
  initPodsScanner();
  initNodesScanner();
});
