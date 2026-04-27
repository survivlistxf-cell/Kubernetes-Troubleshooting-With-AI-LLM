-- Initialize PostgreSQL database with schema for Kubexplain

-- Users table
CREATE TABLE IF NOT EXISTS users (
    id SERIAL PRIMARY KEY,
    username VARCHAR(255) UNIQUE NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    password VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Chats table
CREATE TABLE IF NOT EXISTS chats (
    id SERIAL PRIMARY KEY,
    user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    conversation_id VARCHAR(100),
    -- Optional denormalized title (kept for backward compatibility / reporting)
    conversation_title VARCHAR(255),
    -- Optional denormalized flag mirroring conversations.is_title_custom
    is_title_custom BOOLEAN DEFAULT FALSE,
    user_message TEXT NOT NULL,
    ai_response TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Normalized conversations table (titles live here)
CREATE TABLE IF NOT EXISTS conversations (
    conversation_id VARCHAR(100) PRIMARY KEY,
    user_id INTEGER NULL REFERENCES users(id) ON DELETE SET NULL,
    conversation_title VARCHAR(255),
    is_title_custom BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Conversation context artifacts (Option B1)
-- Stores structured kdiag/1.0 payloads separately from chat messages.
CREATE TABLE IF NOT EXISTS conversation_context (
    id SERIAL PRIMARY KEY,
    conversation_id VARCHAR(100) NOT NULL,
    user_id INTEGER NULL REFERENCES users(id) ON DELETE SET NULL,
    level INTEGER NOT NULL DEFAULT 0,
    source VARCHAR(100) NOT NULL DEFAULT 'unknown',
    payload_json TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Create indexes for better query performance
CREATE INDEX IF NOT EXISTS idx_chats_user_id ON chats(user_id);
CREATE INDEX IF NOT EXISTS idx_chats_created_at ON chats(created_at);
CREATE INDEX IF NOT EXISTS idx_chats_conversation_id ON chats(conversation_id);

CREATE INDEX IF NOT EXISTS idx_conv_user_id ON conversations(user_id);
CREATE INDEX IF NOT EXISTS idx_conv_updated_at ON conversations(updated_at);

CREATE INDEX IF NOT EXISTS idx_ctx_conversation_id ON conversation_context(conversation_id);
CREATE INDEX IF NOT EXISTS idx_ctx_user_id ON conversation_context(user_id);
CREATE INDEX IF NOT EXISTS idx_ctx_created_at ON conversation_context(created_at);

-- Cluster configurations for multi-cluster support
CREATE TABLE IF NOT EXISTS cluster_configs (
    id SERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    display_name VARCHAR(255),
    kubeconfig_path VARCHAR(500) NOT NULL,
    context_name VARCHAR(255),
    default_namespace VARCHAR(100) DEFAULT 'default',
    is_default BOOLEAN DEFAULT FALSE,
    is_active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_cluster_name ON cluster_configs(name);
CREATE INDEX IF NOT EXISTS idx_cluster_active ON cluster_configs(is_active);

-- Sample data (optional, for testing)
-- Uncomment to seed initial data>
-- INSERT INTO users (username, email, password) VALUES 
-- ('testuser', 'test@example.com', '$2a$10$slYQmyNdGzin7olVN3p5/.O9wO2kxaq7VVrXvnHYNrKVUtnCC2ejm');

