ALTER TABLE member ADD COLUMN usage_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE member ADD COLUMN screen_minutes INT;
ALTER TABLE member ADD COLUMN screen_date VARCHAR(10);

CREATE TABLE companion_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    pair_id VARCHAR(36) NOT NULL,
    sender_id VARCHAR(36) NOT NULL,
    kind VARCHAR(16) NOT NULL,
    body VARCHAR(500) NOT NULL,
    created_at BIGINT NOT NULL,
    FOREIGN KEY (sender_id) REFERENCES member(id)
);
CREATE INDEX event_pair ON companion_event(pair_id, id);
CREATE INDEX event_expiry ON companion_event(created_at);

CREATE TABLE shared_place (
    id VARCHAR(36) PRIMARY KEY,
    member_id VARCHAR(36) NOT NULL,
    name VARCHAR(40) NOT NULL,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    radius INT NOT NULL,
    inside BOOLEAN,
    observed_at BIGINT,
    FOREIGN KEY (member_id) REFERENCES member(id)
);
CREATE INDEX place_owner ON shared_place(member_id);

CREATE TABLE push_device (
    token VARCHAR(512) PRIMARY KEY,
    member_id VARCHAR(36) NOT NULL,
    delivered_through BIGINT NOT NULL,
    refreshed_at BIGINT NOT NULL,
    FOREIGN KEY (member_id) REFERENCES member(id)
);
CREATE INDEX push_device_owner ON push_device(member_id);
