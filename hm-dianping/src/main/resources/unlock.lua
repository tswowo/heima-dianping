
-- 比较锁中标识与当前线程标识是否一致
local lockFlag=redis.call('get',KEYS[1])

if lockFlag==ARGV[1] then
    -- 释放锁
    return redis.call('del',KEYS[1])
end
return 0