-- 优惠券id
local voucherId = ARGV[1]
-- 用户id
local userId = ARGV[2]
-- 订单id
local orderId = ARGV[3]

-- 库存key
local stockKey = "seckill:stock:" .. voucherId
-- 订单key
local orderKey = "seckill:order:" .. voucherId

if(tonumber(redis.call("get", stockKey)) <= 0) then
    -- 库存不足
    return 1;
end

if(redis.call("sismember", orderKey, userId)==1)then
    -- 重复下单
    return 2;
end

redis.call("incrby", stockKey, -1)
redis.call("sadd", orderKey, userId)

-- 发送消息到stream消息队列
redis.call("xadd", "stream.orders", "*",
    "voucherId",voucherId,
    "userId",userId,
    "id",orderId)
return 0;