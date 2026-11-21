CREATE TABLE write_guard (id INT PRIMARY KEY);
INSERT INTO write_guard VALUES (1);

CREATE TABLE member (
    id VARCHAR(36) PRIMARY KEY,
    username VARCHAR(32) NOT NULL UNIQUE,
    display_name VARCHAR(32) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    pair_id VARCHAR(36),
    sharing BOOLEAN NOT NULL DEFAULT FALSE,
    share_key VARCHAR(36)
);

CREATE TABLE invitation (
    id VARCHAR(16) PRIMARY KEY,
    member_id VARCHAR(36) NOT NULL UNIQUE,
    expires_at BIGINT NOT NULL,
    FOREIGN KEY (member_id) REFERENCES member (id)
);

CREATE TABLE position_sample (
    id VARCHAR(36) PRIMARY KEY,
    pair_id VARCHAR(36) NOT NULL,
    member_id VARCHAR(36) NOT NULL,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    accuracy DOUBLE PRECISION NOT NULL,
    battery INT NOT NULL,
    charging BOOLEAN NOT NULL,
    recorded_at BIGINT NOT NULL,
    FOREIGN KEY (member_id) REFERENCES member (id)
);
CREATE INDEX sample_route ON position_sample (pair_id, member_id, recorded_at);
CREATE INDEX sample_expiry ON position_sample (recorded_at);
CREATE INDEX member_pair ON member (pair_id);
