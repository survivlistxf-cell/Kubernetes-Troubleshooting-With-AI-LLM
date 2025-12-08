// Sidebar Toggle
const sidebar = document.getElementById('sidebar');
const toggleBtn = document.getElementById('toggle-btn');
let isFirstMessage = true;

toggleBtn.addEventListener('click', () => {
    sidebar.classList.toggle('collapsed');
});

// Auto-collapse sidebar on first message
function autoCollapseSidebar() {
    if (isFirstMessage) {
        sidebar.classList.add('collapsed');
        isFirstMessage = false;
    }
}

// Hide welcome header when first message is sent
function hideWelcomeHeader() {
    const welcomeHeader = document.querySelector('.welcome-header');
    if (welcomeHeader) {
        welcomeHeader.style.display = 'none';
    }
}

// Tab Navigation
document.querySelectorAll('.nav-item').forEach(button => {
    button.addEventListener('click', () => {
        const tabName = button.dataset.tab;
        
        // Remove active class from all buttons and tabs
        document.querySelectorAll('.nav-item').forEach(btn => btn.classList.remove('active'));
        document.querySelectorAll('.tab-content').forEach(tab => tab.classList.remove('active'));
        
        // Add active class to clicked button and corresponding tab
        button.classList.add('active');
        document.getElementById(tabName).classList.add('active');
    });
});

// Chat Functionality
const promptForm = document.getElementById('prompt-form');
const promptInput = document.getElementById('prompt-input');
const messagesArea = document.getElementById('messages');

// Auto-resize textarea
promptInput.addEventListener('input', () => {
    promptInput.style.height = 'auto';
    promptInput.style.height = Math.min(promptInput.scrollHeight, 150) + 'px';
});

// Handle form submission
promptForm.addEventListener('submit', async (e) => {
    e.preventDefault();
    
    const message = promptInput.value.trim();
    if (!message) return;
    
    // Hide welcome header and collapse sidebar on first message
    hideWelcomeHeader();
    autoCollapseSidebar();
    
    // Add user message to chat
    addMessage(message, 'user');
    promptInput.value = '';
    promptInput.style.height = 'auto';
    
    // Show typing indicator
    showTypingIndicator();
    
    try {
        // Send to backend API
        const response = await fetch('http://localhost:8080/api/chat', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
            },
            body: JSON.stringify({ message: message })
        });
        
        if (response.ok) {
            const data = await response.json();
            removeTypingIndicator();
            addMessage(data.response || "I couldn't process that request.", 'assistant');
        } else {
            removeTypingIndicator();
            addMessage("Error: Could not reach the backend server.", 'assistant');
        }
    } catch (error) {
        console.error('Error:', error);
        removeTypingIndicator();
        addMessage("Error: Could not connect to the server. Make sure the backend is running.", 'assistant');
    }
});

// Handle Enter to send, Shift+Enter for newline
promptInput.addEventListener('keydown', (e) => {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        promptForm.dispatchEvent(new Event('submit'));
    }
});

// Add message to chat
function addMessage(text, sender) {
    const messageDiv = document.createElement('div');
    messageDiv.className = `message ${sender}`;
    
    const avatarDiv = document.createElement('div');
    avatarDiv.className = 'message-avatar';
    avatarDiv.textContent = sender === 'user' ? 'You' : 'AI';
    
    const contentDiv = document.createElement('div');
    contentDiv.className = 'message-content';
    contentDiv.textContent = text;
    
    messageDiv.appendChild(avatarDiv);
    messageDiv.appendChild(contentDiv);
    
    messagesArea.appendChild(messageDiv);
    
    // Scroll to bottom
    messagesArea.scrollTop = messagesArea.scrollHeight;
}

// Show typing indicator
function showTypingIndicator() {
    const messageDiv = document.createElement('div');
    messageDiv.className = 'message assistant typing-indicator';
    messageDiv.id = 'typing-indicator';
    
    const avatarDiv = document.createElement('div');
    avatarDiv.className = 'message-avatar';
    avatarDiv.textContent = 'AI';
    
    const contentDiv = document.createElement('div');
    contentDiv.className = 'message-content';
    contentDiv.innerHTML = '<span></span><span></span><span></span>';
    
    messageDiv.appendChild(avatarDiv);
    messageDiv.appendChild(contentDiv);
    
    messagesArea.appendChild(messageDiv);
    messagesArea.scrollTop = messagesArea.scrollHeight;
}

// Remove typing indicator
function removeTypingIndicator() {
    const indicator = document.getElementById('typing-indicator');
    if (indicator) {
        indicator.remove();
    }
}

// Pods Scanner Functionality
const scanBtn = document.getElementById('scan-btn');
const scanResults = document.getElementById('scan-results');
const scanLoading = document.getElementById('scan-loading');
const podsList = document.getElementById('pods-list');

scanBtn.addEventListener('click', async () => {
    scanBtn.disabled = true;
    scanBtn.style.opacity = '0.6';
    scanLoading.style.display = 'block';
    scanResults.style.display = 'none';
    podsList.innerHTML = '';

    try {
        const response = await fetch('http://localhost:8080/api/scan-pods', {
            method: 'GET',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        if (response.ok) {
            const data = await response.json();
            scanLoading.style.display = 'none';
            
            if (data.pods && data.pods.length > 0) {
                scanResults.style.display = 'block';
                data.pods.forEach(pod => {
                    const podDiv = document.createElement('div');
                    podDiv.className = 'pod-item';
                    podDiv.innerHTML = `
                        <h4>📦 ${pod.name}</h4>
                        <div class="pod-info">
                            <div class="pod-info-item">
                                <span class="pod-info-label">Namespace:</span>
                                <span>${pod.namespace}</span>
                            </div>
                            <div class="pod-info-item">
                                <span class="pod-info-label">Status:</span>
                                <span>${pod.status}</span>
                            </div>
                            <div class="pod-info-item">
                                <span class="pod-info-label">Node:</span>
                                <span>${pod.node || 'N/A'}</span>
                            </div>
                            <div class="pod-info-item">
                                <span class="pod-info-label">Containers:</span>
                                <span>${pod.containers}</span>
                            </div>
                            ${pod.restarts ? `<div class="pod-info-item">
                                <span class="pod-info-label">Restarts:</span>
                                <span>${pod.restarts}</span>
                            </div>` : ''}
                            ${pod.age ? `<div class="pod-info-item">
                                <span class="pod-info-label">Age:</span>
                                <span>${pod.age}</span>
                            </div>` : ''}
                        </div>
                    `;
                    podsList.appendChild(podDiv);
                });
            } else {
                scanResults.style.display = 'block';
                podsList.innerHTML = '<p style="text-align: center; opacity: 0.7;">No Kubernetes pods found on this system.</p>';
            }
        } else {
            scanLoading.style.display = 'none';
            scanResults.style.display = 'block';
            podsList.innerHTML = `<p style="text-align: center; color: #d32f2f;">Error: ${data.error || 'Could not scan for pods'}</p>`;
        }
    } catch (error) {
        console.error('Scan error:', error);
        scanLoading.style.display = 'none';
        scanResults.style.display = 'block';
        podsList.innerHTML = `<p style="text-align: center; color: #d32f2f;">Error: ${error.message}</p>`;
    } finally {
        scanBtn.disabled = false;
        scanBtn.style.opacity = '1';
    }
});

// Nodes Scanner Functionality
const scanNodesBtn = document.getElementById('scan-nodes-btn');
const nodesScanResults = document.getElementById('nodes-scan-results');
const nodesScanLoading = document.getElementById('nodes-scan-loading');
const nodesList = document.getElementById('nodes-list');

scanNodesBtn.addEventListener('click', async () => {
    scanNodesBtn.disabled = true;
    scanNodesBtn.style.opacity = '0.6';
    nodesScanLoading.style.display = 'block';
    nodesScanResults.style.display = 'none';
    nodesList.innerHTML = '';

    try {
        const response = await fetch('http://localhost:8080/api/scan-nodes', {
            method: 'GET',
            headers: {
                'Content-Type': 'application/json',
            }
        });

        if (response.ok) {
            const data = await response.json();
            nodesScanLoading.style.display = 'none';
            
            if (data.nodes && data.nodes.length > 0) {
                nodesScanResults.style.display = 'block';
                data.nodes.forEach(node => {
                    const nodeDiv = document.createElement('div');
                    nodeDiv.className = 'node-item';
                    nodeDiv.innerHTML = `
                        <h4>🖥️ ${node.name}</h4>
                        <div class="node-info">
                            <div class="node-info-item">
                                <span class="node-info-label">Status:</span>
                                <span>${node.status}</span>
                            </div>
                            <div class="node-info-item">
                                <span class="node-info-label">Roles:</span>
                                <span>${node.roles || 'N/A'}</span>
                            </div>
                            <div class="node-info-item">
                                <span class="node-info-label">Age:</span>
                                <span>${node.age || 'N/A'}</span>
                            </div>
                            <div class="node-info-item">
                                <span class="node-info-label">Version:</span>
                                <span>${node.version || 'N/A'}</span>
                            </div>
                            ${node.internalIp ? `<div class="node-info-item">
                                <span class="node-info-label">Internal IP:</span>
                                <span>${node.internalIp}</span>
                            </div>` : ''}
                            ${node.externalIp ? `<div class="node-info-item">
                                <span class="node-info-label">External IP:</span>
                                <span>${node.externalIp}</span>
                            </div>` : ''}
                        </div>
                    `;
                    nodesList.appendChild(nodeDiv);
                });
            } else {
                nodesScanResults.style.display = 'block';
                nodesList.innerHTML = '<p style="text-align: center; opacity: 0.7;">No Kubernetes nodes found on this system.</p>';
            }
        } else {
            nodesScanLoading.style.display = 'none';
            nodesScanResults.style.display = 'block';
            nodesList.innerHTML = `<p style="text-align: center; color: #d32f2f;">Error: ${data.error || 'Could not scan for nodes'}</p>`;
        }
    } catch (error) {
        console.error('Nodes scan error:', error);
        nodesScanLoading.style.display = 'none';
        nodesScanResults.style.display = 'block';
        nodesList.innerHTML = `<p style="text-align: center; color: #d32f2f;">Error: ${error.message}</p>`;
    } finally {
        scanNodesBtn.disabled = false;
        scanNodesBtn.style.opacity = '1';
    }
});

console.log('Kubexplain Chat App Loaded! 🚀');
