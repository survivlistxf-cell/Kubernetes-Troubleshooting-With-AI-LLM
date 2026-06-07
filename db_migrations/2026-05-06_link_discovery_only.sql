-- Migrates cluster_links to detection-only mode (B-pure).
-- Manual-declaration columns are dropped; 'source' identifies the detector.
-- Existing manually-declared rows are invalid and are removed first.

TRUNCATE TABLE cluster_links;

ALTER TABLE cluster_links
    ADD COLUMN IF NOT EXISTS source VARCHAR(50);

ALTER TABLE cluster_links
    DROP COLUMN IF EXISTS description,
    DROP COLUMN IF EXISTS test_endpoint;
