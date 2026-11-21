ALTER TABLE member ADD COLUMN app_usage_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE member ADD COLUMN device_status_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE member ADD COLUMN device_model VARCHAR(80);
ALTER TABLE member ADD COLUMN network_type VARCHAR(20);
ALTER TABLE member ADD COLUMN device_status_at BIGINT;

CREATE TABLE app_usage_snapshot (
    member_id VARCHAR(36) PRIMARY KEY,
    usage_date VARCHAR(10) NOT NULL,
    recorded_at BIGINT NOT NULL,
    apps_json TEXT NOT NULL,
    FOREIGN KEY (member_id) REFERENCES member(id)
);

ALTER TABLE member ADD COLUMN device_unlocked_at BIGINT;
ALTER TABLE member ADD COLUMN device_motion VARCHAR(10);
ALTER TABLE member ADD COLUMN candidate_motion VARCHAR(10);
