-- One-time migration for existing PostgreSQL DBs created before conversations/title tracking.
-- Safe to run multiple times.

-- 1) Ensure conversations table exists
CREATE TABLE IF NOT EXISTS conversations (
    conversation_id VARCHAR(100) PRIMARY KEY,
    user_id INTEGER NULL REFERENCES users(id) ON DELETE SET NULL,
    conversation_title VARCHAR(255),
    is_title_custom BOOLEAN DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 2) Ensure chats has conversation grouping + denormalized title columns
ALTER TABLE chats ADD COLUMN IF NOT EXISTS conversation_id VARCHAR(100);
ALTER TABLE chats ADD COLUMN IF NOT EXISTS conversation_title VARCHAR(255);
ALTER TABLE chats ADD COLUMN IF NOT EXISTS is_title_custom BOOLEAN DEFAULT FALSE;

-- 3) Ensure conversations has custom-title flag
ALTER TABLE conversations ADD COLUMN IF NOT EXISTS is_title_custom BOOLEAN DEFAULT FALSE;

-- 4) Backfill conversations from existing chats where possible
-- Create one conversation row per conversation_id, using earliest chat to infer user_id and title.
WITH conv_seed AS (
    SELECT
        c.conversation_id,
        MIN(c.created_at) AS created_at,
        MAX(c.created_at) AS updated_at,
        MIN(c.user_id) AS user_id,
        MIN(c.user_message) AS first_message
    FROM chats c
    WHERE c.conversation_id IS NOT NULL AND c.conversation_id <> ''
    GROUP BY c.conversation_id
)
INSERT INTO conversations (conversation_id, user_id, conversation_title, is_title_custom, created_at, updated_at)
SELECT
    s.conversation_id,
    s.user_id,
    CASE
        WHEN s.first_message IS NULL OR btrim(s.first_message) = '' THEN 'Conversation'
        WHEN length(regexp_replace(s.first_message, E'\\r?\\n', ' ', 'g')) <= 80 THEN regexp_replace(s.first_message, E'\\r?\\n', ' ', 'g')
        ELSE substr(regexp_replace(s.first_message, E'\\r?\\n', ' ', 'g'), 1, 77) || '...'
    END AS conversation_title,
    FALSE AS is_title_custom,
    COALESCE(s.created_at, CURRENT_TIMESTAMP),
    COALESCE(s.updated_at, CURRENT_TIMESTAMP)
FROM conv_seed s
ON CONFLICT (conversation_id) DO NOTHING;

-- 5) Backfill denormalized chats.conversation_title from conversations
UPDATE chats ch
SET conversation_title = cv.conversation_title,
    is_title_custom = cv.is_title_custom
FROM conversations cv
WHERE ch.conversation_id = cv.conversation_id;
