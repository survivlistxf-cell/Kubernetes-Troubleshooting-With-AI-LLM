const express = require('express');
const cors = require('cors');
const path = require('path');

const app = express();
const PORT = process.env.PORT || 3000;

// Middleware
app.use(cors());
app.use(express.json());
app.use(express.static(path.join(__dirname)));

// Serve static files (HTML, CSS, JS)
app.get('/', (req, res) => {
    res.sendFile(path.join(__dirname, 'index.html'));
});

// Health check endpoint
app.get('/api/health', (req, res) => {
    res.json({ status: 'Frontend server is running!' });
});

// Proxy request to backend if needed
app.post('/api/proxy', async (req, res) => {
    try {
        // This can be used to forward requests to backend if needed
        res.json({ message: 'Proxy endpoint ready' });
    } catch (error) {
        res.status(500).json({ error: 'Internal server error' });
    }
});

// 404 handler
app.use((req, res) => {
    res.status(404).sendFile(path.join(__dirname, 'index.html'));
});

// Start server
app.listen(PORT, () => {
    console.log(`🚀 Frontend server running on http://localhost:${PORT}`);
    console.log(`📱 Open browser at http://localhost:${PORT}`);
});
