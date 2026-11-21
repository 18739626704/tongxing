ALTER TABLE member ADD COLUMN location_unavailable BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE companion_event ADD COLUMN client_request VARCHAR(36);
ALTER TABLE companion_event ADD COLUMN location_requested BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE companion_event ADD COLUMN received_at BIGINT;
ALTER TABLE companion_event ADD COLUMN viewed_at BIGINT;
CREATE UNIQUE INDEX event_client_request ON companion_event(sender_id, client_request);
CREATE TABLE companion_place (
    id VARCHAR(36) PRIMARY KEY,
    pair_id VARCHAR(36) NOT NULL,
    owner_id VARCHAR(36) NOT NULL,
    name VARCHAR(40) NOT NULL,
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    watching BOOLEAN NOT NULL DEFAULT FALSE,
    armed BOOLEAN NOT NULL DEFAULT FALSE,
    inside_at BIGINT,
    observed_at BIGINT,
    FOREIGN KEY (owner_id) REFERENCES member(id)
);
CREATE INDEX companion_place_pair_owner ON companion_place(pair_id, owner_id);
