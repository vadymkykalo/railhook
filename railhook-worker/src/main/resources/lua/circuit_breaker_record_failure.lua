-- Atomically record failure and evaluate failure rate
-- KEYS[1] = failsKey
-- KEYS[2] = callsKey
-- ARGV[1] = windowTtlSeconds
-- ARGV[2] = minimumNumberOfCalls
-- ARGV[3] = failureRateThreshold
-- Returns: {failCount, callCount, shouldTrip (1/0), failureRate}

local failsKey = KEYS[1]
local callsKey = KEYS[2]
local windowTtl = tonumber(ARGV[1])
local minCalls = tonumber(ARGV[2])
local failRateThreshold = tonumber(ARGV[3])

local failCount = redis.call('INCR', failsKey)
redis.call('EXPIRE', failsKey, windowTtl)

local callCount = redis.call('INCR', callsKey)
redis.call('EXPIRE', callsKey, windowTtl)

local shouldTrip = 0
local failureRate = 0

if callCount >= minCalls then
    failureRate = (failCount * 100) / callCount
    if failureRate >= failRateThreshold then
        shouldTrip = 1
    end
end

return {failCount, callCount, shouldTrip, failureRate}
