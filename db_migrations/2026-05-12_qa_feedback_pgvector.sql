-- ============================================================
-- QA Feedback table with pgvector embedding support
-- Run manually in pgAdmin AFTER:
--   1. `CREATE EXTENSION IF NOT EXISTS vector;`  (or pgAdmin UI)
--   2. `ollama pull nomic-embed-text`
-- ============================================================

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS qa_feedback (
  id             BIGSERIAL PRIMARY KEY,
  conversation_id VARCHAR(100) NOT NULL,
  user_question  TEXT NOT NULL,
  ai_response    TEXT NOT NULL,
  embedding      vector(768),                        -- nomic-embed-text → 768 dims
  feedback       INTEGER NOT NULL DEFAULT 0,         -- 0=neutral, 1=like, -1=dislike
  source_urls    TEXT,
  created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- HNSW index for fast cosine-distance ANN search (used in next prompt)
CREATE INDEX IF NOT EXISTS qa_feedback_embedding_hnsw
  ON qa_feedback USING hnsw (embedding vector_cosine_ops)
  WHERE embedding IS NOT NULL;

-- Fast lookup by conversation (used when routing feedback)
CREATE INDEX IF NOT EXISTS qa_feedback_conversation_id
  ON qa_feedback (conversation_id);

-- Partial index — only rows with positive feedback matter for CBR hints
CREATE INDEX IF NOT EXISTS qa_feedback_feedback_pos
  ON qa_feedback (feedback) WHERE feedback >= 1;
