local redis_time = redis.call('TIME')
local now = tonumber(redis_time[1]) * 1000 + math.floor(tonumber(redis_time[2]) / 1000)
local expires_at = now + tonumber(ARGV[3])

for index = 1, #KEYS do
    redis.call('ZREMRANGEBYSCORE', KEYS[index], '-inf', now)
end

if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[1]) then
    local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
    return {0, 1, math.max(1, tonumber(oldest[2]) - now)}
end

if redis.call('ZCARD', KEYS[2]) >= tonumber(ARGV[2]) then
    local oldest = redis.call('ZRANGE', KEYS[2], 0, 0, 'WITHSCORES')
    return {0, 2, math.max(1, tonumber(oldest[2]) - now)}
end

for index = 1, #KEYS do
    redis.call('ZADD', KEYS[index], expires_at, ARGV[4])
    redis.call('PEXPIRE', KEYS[index], ARGV[3])
end

return {1, 0, 0}
