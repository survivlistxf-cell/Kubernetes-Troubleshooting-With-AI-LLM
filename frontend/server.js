const express = require('express');
const cors = require('cors');
const path = require('path');
const http = require('http');
const https = require('https');
const { URL } = require('url');

const app = express();
const PORT = process.env.PORT || 3000;
// Backend resolution strategy:
// - If BACKEND_URL is set, always use that.
// - Otherwise, auto-detect a reachable backend (local dev vs docker-compose).
//   This avoids long timeouts when `backend:8080` isn't resolvable in local mode.
const backendCandidateUrls = Array.from(new Set([
    process.env.BACKEND_URL,
    'http://localhost:8080',
    'http://backend:8080'
].filter(Boolean)));

let resolvedBackendUrl = process.env.BACKEND_URL || null;
let lastResolveAt = 0;
const RESOLVE_TTL_MS = 10_000;

if (backendCandidateUrls.length === 0) {
    backendCandidateUrls.push('http://localhost:8080');
}

// Middleware
app.use(cors());
app.use(express.json());

// Static files with cache disabled (dev-friendly)
// This prevents stale frontend JS/CSS after edits without needing cache-busting query strings.
app.use(express.static(path.join(__dirname), {
    etag: false,
    lastModified: false,
    setHeaders: (res, filePath) => {
        const p = String(filePath || '').toLowerCase();

        // Strong no-cache for JS (especially app.js)
        if (p.endsWith('.js')) {
            res.setHeader('Cache-Control', 'no-store, no-cache, must-revalidate, proxy-revalidate');
            res.setHeader('Pragma', 'no-cache');
            res.setHeader('Expires', '0');
            res.setHeader('Surrogate-Control', 'no-store');
            return;
        }

        // Conservative no-cache for HTML/CSS too (optional, keeps dev behavior consistent)
        if (p.endsWith('.html') || p.endsWith('.css')) {
            res.setHeader('Cache-Control', 'no-cache, must-revalidate');
            res.setHeader('Pragma', 'no-cache');
            res.setHeader('Expires', '0');
        }
    }
}));

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
    const baseUrl = await resolveBackendUrl();
    if (!baseUrl) {
        if (!res.headersSent) {
            res.status(503).json({
                error: 'Backend service unavailable',
                details: `No reachable backend found. Tried ${backendCandidateUrls.join(', ')}`
            });
        }
        return;
    }

    proxyRequest(req, res, baseUrl, 0);
});

async function resolveBackendUrl() {
    // Explicit override wins.
    if (process.env.BACKEND_URL) {
        resolvedBackendUrl = process.env.BACKEND_URL;
        lastResolveAt = Date.now();
        return resolvedBackendUrl;
    }

    const now = Date.now();
    if (resolvedBackendUrl && (now - lastResolveAt) < RESOLVE_TTL_MS) {
        return resolvedBackendUrl;
    }

    for (const base of backendCandidateUrls) {
        if (!base) continue;
        if (base === 'http://backend:8080' && process.env.DOCKER_COMPOSE !== '1') {
            // In local mode, skip docker-only hostname to avoid DNS delays.
            continue;
        }

        // Probe quickly (connectivity check).
        // eslint-disable-next-line no-await-in-loop
        const ok = await probeBackend(base);
        if (ok) {
            resolvedBackendUrl = base;
            lastResolveAt = now;
            return resolvedBackendUrl;
        }
    }

    resolvedBackendUrl = null;
    lastResolveAt = now;
    return null;
}

function probeBackend(baseUrl) {
    return new Promise((resolve) => {
        const healthPaths = ['/api/hello', '/api/status', '/actuator/health'];

        const tryNext = (idx) => {
            if (idx >= healthPaths.length) {
                resolve(false);
                return;
            }

            let target;
            try {
                target = new URL(healthPaths[idx], baseUrl);
            } catch {
                tryNext(idx + 1);
                return;
            }

            const protocolClient = target.protocol === 'https:' ? https : http;
            const pingReq = protocolClient.request({
                hostname: target.hostname,
                port: target.port || (target.protocol === 'https:' ? 443 : 80),
                path: target.pathname + target.search,
                method: 'GET',
                timeout: 800
            }, (pingRes) => {
                const ok = pingRes.statusCode && pingRes.statusCode >= 200 && pingRes.statusCode < 400;
                pingRes.resume();
                if (ok) {
                    resolve(true);
                } else {
                    tryNext(idx + 1);
                }
            });

            pingReq.on('timeout', () => {
                try { pingReq.destroy(); } catch { }
                tryNext(idx + 1);
            });
            pingReq.on('error', () => tryNext(idx + 1));
            pingReq.end();
        };

        tryNext(0);
    });
}

function proxyRequest(req, res, baseUrl, index) {
    // If we've already started sending a response (or the client disconnected),
    // never attempt another proxy attempt.
    if (res.headersSent || res.writableEnded || res.destroyed) {
        return;
    }

    const candidates = [
        baseUrl,
        ...backendCandidateUrls.filter(u => u && u !== baseUrl)
    ];

    if (index >= candidates.length) {
        if (!res.headersSent) {
            res.status(503).json({
                error: 'Backend service unavailable',
                details: `Tried ${candidates.join(', ')} and none responded.`
            });
        }
        return;
    }

    const candidateUrl = candidates[index];
    let targetUrl;
    try {
        targetUrl = new URL(req.originalUrl, candidateUrl);
    } catch (error) {
        console.error(`❌ Invalid backend URL: ${candidateUrl}`, error.message);
        proxyRequest(req, res, baseUrl, index + 1);
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
        // Node details can take >15s on slow clusters (first kubectl call). Keep proxy generous.
        timeout: 30000
    };

    console.log(`📡 Proxying ${req.method} ${req.originalUrl} to ${targetUrl.href}`);

    let finished = false;
    const finalize = () => {
        finished = true;
    };

    const proxyReq = protocolClient.request(options, (proxyRes) => {
        if (finished) {
            // We already moved on to another backend candidate; discard this response.
            proxyRes.resume();
            return;
        }

        // Mark as finished because this attempt got a response.
        finalize();

        // If the downstream already started/ended, don't write headers.
        if (res.headersSent || res.writableEnded || res.destroyed) {
            proxyRes.resume();
            return;
        }

        res.writeHead(proxyRes.statusCode, proxyRes.headers);
        proxyRes.pipe(res);
    });

    const retryNext = (reason) => {
        if (finished) return;
        if (res.headersSent || res.writableEnded || res.destroyed) {
            finalize();
            return;
        }
        console.error(reason);
        finalize();
        try {
            proxyReq.destroy();
        } catch { }
        proxyRequest(req, res, baseUrl, index + 1);
    };

    // Ensure timeout triggers reliably on the request socket.
    proxyReq.setTimeout(30000, () => {
        retryNext(`❌ Backend request timeout for ${candidateUrl}`);
    });

    proxyReq.on('error', (error) => {
        retryNext(`❌ Proxy error to ${candidateUrl}: ${error.message}`);
    });

    // If client closes connection, abort upstream.
    res.on('close', () => {
        try {
            proxyReq.destroy();
        } catch { }
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
