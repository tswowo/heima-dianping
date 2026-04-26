package com.hmdp.utils;

public class RedisConstants {
    public static final String LOGIN_CODE_KEY = "login:code:";
    public static final Long LOGIN_CODE_TTL = 2L;
    public static final String LOGIN_USER_KEY = "login:token:";
    public static final Long LOGIN_USER_TTL = 36000L;
    public static final String CACHE_SHOP_TYPE_KEY = "cache:shopType:";
    public static final Long CACHE_SHOP_TYPE_TTL = 36000L;
    public static final Long CACHE_SHOP_RANDOM_TTL = 600L;

    public static final Long CACHE_NULL_TTL = 2L;
    public static final Long CACHE_NULL_RANDOM_TTL = 60L;

    public static final Long CACHE_SHOP_TTL = 30L;
    public static final String CACHE_SHOP_KEY = "cache:shop:";

    public static final String LOCK_SHOP_KEY = "lock:shop:";
    public static final Long LOCK_SHOP_TTL = 10L;

    public static final String SECKILL_STOCK_KEY = "seckill:stock:";
    public static final String BLOG_LIKED_KEY = "blog:liked:";
    public static final String FEED_KEY = "feed:";
    public static final String SHOP_GEO_KEY = "shop:geo:";
    public static final String USER_SIGN_KEY = "sign:";

    /**
     * 生成带随机值的TTL（防止缓存雪崩）
     *
     * @param baseTtlMinutes     基础TTL（分钟）
     * @param randomRangeSeconds 随机范围（秒）
     * @return 带随机值的TTL（秒）
     */
    static public long generateRandomTtl(long baseTtlMinutes, long randomRangeSeconds) {
        long randomSeconds = (long) (Math.random() * randomRangeSeconds);
        return baseTtlMinutes * 60L + randomSeconds;
    }
}
