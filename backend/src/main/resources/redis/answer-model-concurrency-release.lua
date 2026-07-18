for index = 1, #KEYS do
    redis.call('ZREM', KEYS[index], ARGV[1])
    if redis.call('ZCARD', KEYS[index]) == 0 then
        redis.call('DEL', KEYS[index])
    end
end

return 1
