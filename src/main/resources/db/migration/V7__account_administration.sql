ALTER TABLE member ADD COLUMN disabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE member ADD COLUMN session_version INT NOT NULL DEFAULT 0;
ALTER TABLE member ADD COLUMN admin_location_allowed BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE admin_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    action VARCHAR(24) NOT NULL,
    target_id VARCHAR(36) NOT NULL,
    occurred_at BIGINT NOT NULL
);
CREATE INDEX admin_audit_time ON admin_audit(occurred_at);
