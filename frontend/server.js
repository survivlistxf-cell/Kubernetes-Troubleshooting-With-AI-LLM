const express = require('express');
const cors = require('cors');
const axios = require('axios');

const app = express();
const PORT = 3000;

// Middleware
app.use(cors());
app.use(express.json());

// Backend URL
const BACKEND_URL = 'http://localhost:8080';

// Routes
app.get('/', (req, res) => {
  res.json({
    message: 'Welcome to Node.js Frontend Server',
    version: '1.0.0'
  });
});

// Proxy to Spring Boot Backend
app.get('/api/hello', async (req, res) => {
  try {
    const response = await axios.get(`${BACKEND_URL}/api/hello`);
    res.json({
      message: response.data,
      source: 'Spring Boot Backend'
    });
  } catch (error) {
    res.status(500).json({
      error: 'Failed to reach backend',
      details: error.message
    });
  }
});

// Get backend status
app.get('/api/status', async (req, res) => {
  try {
    const response = await axios.get(`${BACKEND_URL}/api/status`);
    res.json({
      backend: response.data,
      frontend: 'Node.js is running!'
    });
  } catch (error) {
    res.status(500).json({
      error: 'Backend is not available',
      details: error.message
    });
  }
});

// Health check
app.get('/health', (req, res) => {
  res.json({ status: 'OK', timestamp: new Date().toISOString() });
});

// Start server
app.listen(PORT, () => {
  console.log(`Node.js server running on http://localhost:${PORT}`);
  console.log(`Backend URL: ${BACKEND_URL}`);
});
