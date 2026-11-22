CREATE INDEX sample_member_time ON position_sample(member_id, recorded_at);
CREATE INDEX event_sender_id ON companion_event(sender_id, id);
CREATE INDEX companion_place_owner ON companion_place(owner_id);
