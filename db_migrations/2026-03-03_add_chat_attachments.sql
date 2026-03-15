-- Add chat attachments table (persist user attachments separately from chat messages)
-- Safe to run multiple times.

CREATE TABLE IF NOT EXISTS chat_attachments (
    id SERIAL PRIMARY KEY,
    conversation_id VARCHAR(100) NOT NULL,
    chat_id INTEGER NULL REFERENCES chats(id) ON DELETE CASCADE,
    user_id INTEGER NULL REFERENCES users(id) ON DELETE SET NULL,
    file_name VARCHAR(255) NOT NULL,
    mime_type VARCHAR(120) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    content_encoding VARCHAR(20) NOT NULL DEFAULT 'identity',
    content BYTEA NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_att_conversation_id ON chat_attachments(conversation_id);
CREATE INDEX IF NOT EXISTS idx_att_chat_id ON chat_attachments(chat_id);
CREATE INDEX IF NOT EXISTS idx_att_user_id ON chat_attachments(user_id);
CREATE INDEX IF NOT EXISTS idx_att_created_at ON chat_attachments(created_at);
CREATE INDEX IF NOT EXISTS idx_att_sha256 ON chat_attachments(sha256);

-- Ensure conversation_context exists (if using SQL migrations-only env)
CREATE TABLE IF NOT EXISTS conversation_context (
    id SERIAL PRIMARY KEY,
    conversation_id VARCHAR(100) NOT NULL,
    user_id INTEGER NULL REFERENCES users(id) ON DELETE SET NULL,
    level INTEGER NOT NULL DEFAULT 0,
    source VARCHAR(100) NOT NULL DEFAULT 'unknown',
    payload_json TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ctx_conversation_id ON conversation_context(conversation_id);
CREATE INDEX IF NOT EXISTS idx_ctx_user_id ON conversation_context(user_id);
CREATE INDEX IF NOT EXISTS idx_ctx_created_at ON conversation_context(created_at);
