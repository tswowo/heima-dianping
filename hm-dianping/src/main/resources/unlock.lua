local key=KEYS[1]
local threadId=ARGV[1]
local releaseTime=ARGV[2]

-- 判断锁是否存在且被自己持有
if redis.call('hexists',key,threadId)==0 then
    -- 锁不被自己持有
    return 0
end

local count=redis.call('hincrby',key,threadId,-1)

if count>0 then
    -- 锁未完全释放,刷新有效期
    redis.call('expire',key,releaseTime)
    return 1
else
    -- 锁已完全释放,删除锁
    redis.call('del',key)
    return 1
end
