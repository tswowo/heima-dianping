-- 优惠券id
local voucherId = ARGV[1]
-- 用户id
local userId = ARGV[2]

-- 库存key
local stockKey = "seckill:stock:" .. voucherId
-- 订单key
local orderKey = "seckill:order:" .. userId

if(tonumber(redis.call("get", stockKey)) <= 0) then
    -- 库存不足
    return 1;
end

if(redis.call("sismember", orderKey,userId)==1)then
    -- 重复下单
    return 2;
end

redis.call("incrby", stockKey, -1)
redis.call("sadd", orderKey, userId)
return 0;