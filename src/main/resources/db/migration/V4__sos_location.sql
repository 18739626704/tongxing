ALTER TABLE companion_event ADD COLUMN sos_sample_id VARCHAR(36);
ALTER TABLE companion_event ADD CONSTRAINT event_sos_sample
    FOREIGN KEY (sos_sample_id) REFERENCES position_sample (id) ON DELETE SET NULL;
