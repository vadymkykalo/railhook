-- The exact bytes of an incoming webhook whose body does not survive being stored as text.
--
-- body_raw is TEXT, filled by decoding the request as UTF-8. That is lossless for the JSON and
-- form bodies nearly every provider sends, and it is what the dashboard shows. It is not lossless
-- for everything else: bytes that are not valid UTF-8 (another charset, binary, a gzip body) came
-- back with replacement characters, and a Forward sent that decoded copy on, so the Destination
-- received something other than what the provider sent. A body containing a NUL byte could not be
-- stored at all, because PostgreSQL text cannot hold one, and ingress answered 500.
--
-- Filled only for those bodies, rather than for every event: copying each JSON body a second time
-- would double what incoming_events holds for no gain, since body_raw already encodes back to the
-- same bytes. A Forward sends body_bytes when it is set and the UTF-8 encoding of body_raw when it
-- is not, which for every row written before this migration is what it sent before.
--
-- Nullable with no default, so adding it rewrites nothing and holds no lock worth mentioning on a
-- table that grows.

ALTER TABLE incoming_events ADD COLUMN body_bytes BYTEA;

COMMENT ON COLUMN incoming_events.body_bytes IS
    'The body exactly as received, only when it is not valid UTF-8 or contains a NUL byte; otherwise null and body_raw encodes back to it.';
