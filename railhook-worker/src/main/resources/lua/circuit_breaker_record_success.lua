-- Atomically record success and evaluate slow call rate
-- KEYS[1] = callsKey
-- KEYS[2] = slowKey
-- ARGV[1] = windowTtlSeconds
-- ARGV[2] = durationMs
-- ARGV[3] = slowCallThresholdMs
-- ARGV[4] = minimumNumberOfCalls
-- ARGV[5] = slowCallRateThreshold
-- Returns: {callCount, slowCount, shouldTrip (1/0)}

local callsKey = KEYS[1]
local slowKey = KEYS[2]
local windowTtl = tonumber(ARGV[1])
local durationMs = tonumber(ARGV[2])
local slowThreshold = tonumber(ARGV[3])
local minCalls = tonumber(ARGV[4])
local slowRateThreshold = tonumber(ARGV[5])

local callCount = redis.call('INCR', callsKey)
redis.call('EXPIRE', callsKey, windowTtl)

local slowCount = 0
local shouldTrip = 0

if durationMs >= slowThreshold then
    slowCount = redis.call('INCR', slowKey)
    redis.call('EXPIRE', slowKey, windowTtl)
    
    if callCount >= minCalls then
        local slowRate = (slowCount * 100) / callCount
        if slowRate >= slowRateThreshold then
            shouldTrip = 1
        end
    end
end

return {callCount, slowCount, shouldTrip}
