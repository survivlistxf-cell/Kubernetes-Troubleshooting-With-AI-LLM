// Global config + shared state (ESM)
export const API_URL = '/api';

export const state = {
  // UI state
  isFirstMessage: true,

  // Conversation
  conversationIdKey: 'conversationId',

  // Draft attachments (before send)
  attachedFiles: [],  //populat inainte de a trimite mesajul, dupa ce trimit mesajul se goleste

  // Scan caches
  lastScannedPods: [],
  lastScannedNodes: [],

  // Multi-cluster support
  clusters: [],           // All cluster configs from backend
  activeClusterId: null,  // Currently selected cluster ID (null = default)

  // Details modal caches
  selectedPodForDetails: null,
  selectedPodDetailsPayload: null,
  selectedNodeForDetails: null,
  selectedNodeDetailsPayload: null,

  // Last bulk-add snapshots for pods/nodes scanners
  // shape: { signature: string, attachmentName: string }
  lastPodBulkAdd: null,
  lastNodeBulkAdd: null,

  // Chat attachment rendering + preview
  sentMessageAttachments: new Map(), // messageId -> file[] (pentru atasamente din cache-ul browserului, abia trimise, pentru preview imediat)
  attachmentContentCache: new Map(), // attachmentId -> {content,name,type,size} (pentru atasamente din db, incarcate din istoric, pentru a nu le mai cere din nou din db)
};

