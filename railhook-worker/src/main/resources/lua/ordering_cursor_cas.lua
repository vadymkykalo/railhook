-- Atomic compare-and-set for the "last delivered sequence" Redis cache.
-- Only advances the key if newVal is strictly greater than the current value,
-- which prevents two concurrent markDelivered() calls (or a call racing a
-- Redis TTL expiry/flush) from ever moving the cursor backwards.
-- KEYS[1] = delivered-seq key
-- ARGV[1] = candidate value (the authoritative Postgres cursor after upsert)
-- ARGV[2] = TTL in milliseconds
-- Returns: 1 if the key was advanced, 0 if the existing value was already >= newVal

local key = KEYS[1]
local newVal = tonumber(ARGV[1])
local ttlMs = tonumber(ARGV[2])

local current = redis.call('GET', key)

if current == false or tonumber(current) < newVal then
    redis.call('SET', key, newVal, 'PX', ttlMs)
    return 1
else
    return 0
end
