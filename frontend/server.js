const express = require('express');
const cors = require('cors');
const path = require('path');
const http = require('http');

const app = express();
const PORT = process.env.PORT || 3000;
const BACKEND_URL = process.env.BACKEND_URL || 'http://backend:8080';

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

// Proxy all /api/* requests to backend
app.all('/api/*', async (req, res) => {
    const apiPath = req.path;
    const backendUrl = `${BACKEND_URL}${apiPath}`;
    
    console.log(`📡 Proxying ${req.method} ${apiPath} to ${backendUrl}`);
    
    try {
        const options = {
            hostname: new URL(BACKEND_URL).hostname,
            port: new URL(BACKEND_URL).port || 8080,
            path: apiPath,
            method: req.method,
            headers: {
                'Content-Type': 'application/json',
                ...req.headers
            }
        };
        
        const proxyReq = http.request(options, (proxyRes) => {
            res.writeHead(proxyRes.statusCode, proxyRes.headers);
            proxyRes.pipe(res);
        });
        
        proxyReq.on('error', (error) => {
            console.error('❌ Proxy error:', error);
            res.status(503).json({ error: 'Backend service unavailable', details: error.message });
        });
        
        if (req.body && Object.keys(req.body).length > 0) {
            proxyReq.write(JSON.stringify(req.body));
        }
        proxyReq.end();
    } catch (error) {
        console.error('❌ Proxy error:', error);
        res.status(500).json({ error: 'Internal server error', details: error.message });
    }
});

// 404 handler
app.use((req, res) => {
    res.status(404).sendFile(path.join(__dirname, 'index.html'));
});

// Start server
app.listen(PORT, () => {
    console.log(`🚀 Frontend server running on http://0.0.0.0:${PORT}`);
    console.log(`📱 Proxying /api/* to ${BACKEND_URL}`);
});
