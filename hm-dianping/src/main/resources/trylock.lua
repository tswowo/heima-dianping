local key=KEYS[1]
local threadId=ARGV[1]
local releaseTime=ARGV[2]

-- 判断锁是否存在
if redis.call('exists',key)==0 then
    -- 不存在,则获取锁
    redis.call('hset',key,threadId,1)
    -- 设置锁的过期时间
    redis.call('expire',key,releaseTime)
    return 1
end

-- 锁存在,判断锁的线程id是否一致
if redis.call('hexists',key,threadId)==1 then
    -- 锁的线程id一致,则获取锁,锁计数器加1
    redis.call('hincrby',key,threadId,1)
    -- 刷新锁的过期时间
    redis.call('expire',key,releaseTime)
    return 1
end

-- 锁存在,线程id不一致,不可上锁,则返回失败
return 0
