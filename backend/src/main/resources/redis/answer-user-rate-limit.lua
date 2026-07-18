local current = tonumber(redis.call('GET', KEYS[1]) or '0')
local limit = tonumber(ARGV[1])

if current >= limit then
    return {0, redis.call('PTTL', KEYS[1])}
end

current = redis.call('INCR', KEYS[1])
if current == 1 then
    redis.call('PEXPIRE', KEYS[1], ARGV[2])
end

return {1, redis.call('PTTL', KEYS[1])}
