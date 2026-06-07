-- Adds support for declaring connectivity links between Kubernetes clusters
-- and tracking the result of periodic / on-demand connectivity tests.

CREATE TABLE IF NOT EXISTS cluster_links (
    id BIGSERIAL PRIMARY KEY,
    source_cluster_id BIGINT NOT NULL REFERENCES cluster_configs(id) ON DELETE CASCADE,
    target_cluster_id BIGINT NOT NULL REFERENCES cluster_configs(id) ON DELETE CASCADE,
    link_type VARCHAR(50) NOT NULL,           -- vpn | mesh | direct | tunnel | other
    description VARCHAR(500),
    test_endpoint VARCHAR(500),               -- e.g. http://my-svc.default.svc.cluster.local
    last_test_status VARCHAR(20),             -- unknown | connected | failed | testing
    last_test_at TIMESTAMP,
    last_test_message TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT cluster_links_no_self CHECK (source_cluster_id <> target_cluster_id),
    CONSTRAINT cluster_links_unique UNIQUE (source_cluster_id, target_cluster_id)
);

CREATE INDEX IF NOT EXISTS idx_cluster_links_source ON cluster_links(source_cluster_id);
CREATE INDEX IF NOT EXISTS idx_cluster_links_target ON cluster_links(target_cluster_id);
