ALTER TABLE outbox_messages ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now();

-- Auto-update on every row change
CREATE OR REPLACE FUNCTION update_outbox_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_outbox_updated_at
    BEFORE UPDATE ON outbox_messages
    FOR EACH ROW EXECUTE FUNCTION update_outbox_updated_at();
