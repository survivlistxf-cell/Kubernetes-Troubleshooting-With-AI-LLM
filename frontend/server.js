const express = require('express');
const cors = require('cors');
const path = require('path');
const http = require('http');
const https = require('https');
const { URL } = require('url');

const app = express();
const PORT = process.env.PORT || 3000;
const backendCandidateUrls = Array.from(new Set([
    process.env.BACKEND_URL,
    'http://localhost:8080',
    'http://backend:8080'
].filter(Boolean)));

if (backendCandidateUrls.length === 0) {
    backendCandidateUrls.push('http://localhost:8080');
}

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
app.all('/api/*', (req, res) => proxyRequest(req, res, 0));

function proxyRequest(req, res, index) {
    if (index >= backendCandidateUrls.length) {
        if (!res.headersSent) {
            res.status(503).json({
                error: 'Backend service unavailable',
                details: `Tried ${backendCandidateUrls.join(', ')} and none responded.`
            });
        }
        return;
    }

    const candidateUrl = backendCandidateUrls[index];
    let targetUrl;
    try {
        targetUrl = new URL(req.originalUrl, candidateUrl);
    } catch (error) {
        console.error(`❌ Invalid backend URL: ${candidateUrl}`, error.message);
        proxyRequest(req, res, index + 1);
        return;
    }

    const protocolClient = targetUrl.protocol === 'https:' ? https : http;
    const options = {
        hostname: targetUrl.hostname,
        port: targetUrl.port || (targetUrl.protocol === 'https:' ? 443 : 80),
        path: targetUrl.pathname + targetUrl.search,
        method: req.method,
        headers: {
            ...req.headers,
            host: targetUrl.host
        },
        timeout: 15000
    };

    console.log(`📡 Proxying ${req.method} ${req.originalUrl} to ${targetUrl.href}`);

    const proxyReq = protocolClient.request(options, (proxyRes) => {
        res.writeHead(proxyRes.statusCode, proxyRes.headers);
        proxyRes.pipe(res);
    });

    proxyReq.on('timeout', () => {
        console.error(`❌ Backend request timeout for ${candidateUrl}`);
        proxyReq.destroy();
        proxyRequest(req, res, index + 1);
    });

    proxyReq.on('error', (error) => {
        console.error(`❌ Proxy error to ${candidateUrl}:`, error.message);
        proxyRequest(req, res, index + 1);
    });

    const methodsWithBody = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);
    if (methodsWithBody.has(req.method) && req.body && Object.keys(req.body).length > 0) {
        proxyReq.write(JSON.stringify(req.body));
    }

    proxyReq.end();
}

// 404 handler
app.use((req, res) => {
    res.status(404).sendFile(path.join(__dirname, 'index.html'));
});

// Start server
app.listen(PORT, () => {
    console.log(`🚀 Frontend server running on http://0.0.0.0:${PORT}`);
    console.log(`📱 Trying to proxy /api/* to: ${backendCandidateUrls.join(', ')}`);
});
