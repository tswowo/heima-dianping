package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Component
@Slf4j
public class CacheClient {

    final private StringRedisTemplate stringRedisTemplate;
    private static final ExecutorService CACHE_THREAD_POOL = Executors.newFixedThreadPool(10);

    private boolean tryLock(String key) {
        //setnx,只有redis中不存在key才会成功设置,设置失败说明已经有互斥锁
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10L, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key) {
        //删除redis中key的互斥锁
        stringRedisTemplate.delete(key);
    }

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value, long baseTtl, TimeUnit baseUnit, long randomRange, TimeUnit randomUnit) {
        //写入Redis缓存
        log.debug("写入缓存：{}", value);
        stringRedisTemplate.opsForValue().set(
                key
                , JSONUtil.toJsonStr(value)
                , RedisConstants.generateRandomTtlInSeconds(
                        baseTtl
                        , baseUnit
                        , randomRange
                        , randomUnit)
                , TimeUnit.SECONDS);
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        //封装逻辑过期时间
        RedisData redisData = new RedisData(
                LocalDateTime.now().plusSeconds(unit.toSeconds(time))
                , value);
        //写入Redis缓存
        log.debug("写入缓存：{}", redisData);
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }


    public <T, ID> T queryByIdWithPassThrough(String keyPrefix, ID id, Class<T> type, Function<ID, T> dbFallback
            , long baseTtl, TimeUnit baseUnit, long randomRange, TimeUnit randomUnit) {
        String cacheKey = keyPrefix + id;
        //先查询redis缓存
        String cacheJson = stringRedisTemplate.opsForValue().get(cacheKey);

        if (StrUtil.isNotBlank(cacheJson)) {
            //命中缓存则反序列化,返回数据
            log.debug("{}命中缓存,返回数据:{}", cacheKey, cacheJson);
            return JSONUtil.toBean(cacheJson, type);
        }
        if (cacheJson != null) {
            //若命中为空值，则返回null
            log.warn("{}命中空值缓存,返回空值", cacheKey);
            return null;
        }
        //未命中则查数据库
        log.debug("{}未命中缓存,查询数据库", cacheKey);
        T data = dbFallback.apply(id);
        //数据库没有返回404
        if (data == null) {
            //将空值写入redis，减轻缓存穿透的影响
            log.warn("{}不存在于数据库,写入空值缓存,返回空值", cacheKey);
            this.set(cacheKey, ""
                    , RedisConstants.CACHE_NULL_TTL, TimeUnit.MINUTES
                    , RedisConstants.CACHE_NULL_RANDOM_TTL, TimeUnit.SECONDS);
            return null;
        }
        //写入缓存
        log.debug("{}写入缓存,返回数据:{}", cacheKey, data);
        this.set(cacheKey, data, baseTtl, baseUnit, randomRange, randomUnit);
        //返回数据到前端
        return data;
    }


    public <T, ID> T queryByIdWithLogicalExpire(String keyPrefix, String lockKeyPrefix, ID id, Class<T> type, Function<ID, T> dbFallback
            , long baseTtl, TimeUnit baseUnit) {
        String key = keyPrefix + id;
        //先查询redis缓存
        String redisDataJson = stringRedisTemplate.opsForValue().get(key);

        if (redisDataJson == null) {
            //未命中热点数据则返回null
            return null;
        }
        if (StrUtil.isBlank(redisDataJson)) {
            //命中空值则返回空值
            return null;
        }

        log.debug("{}命中缓存:{}", id, redisDataJson);
        // 检查是逻辑过期的热点数据或普通缓存数据
        // 尝试解析为 JSONObject 来判断数据结构
        JSONObject jsonObject = JSONUtil.parseObj(redisDataJson);
        if (jsonObject.containsKey("expireTime")) {
            //处理逻辑过期的热点数据
            RedisData redisData = JSONUtil.toBean(redisDataJson, RedisData.class);
            //命中缓存则检查逻辑过期时间
            if (redisData.getExpireTime().isAfter(LocalDateTime.now())) {
                //若未过期,返回命中的缓存数据
                return JSONUtil.toBean((JSONObject) redisData.getData(), type);
            }
            //若已过期,进行缓存重建
            //获取互斥锁
            String lockKey = lockKeyPrefix + id;
            if (tryLock(lockKey)) {
                //获取互斥锁成功,开启缓存重建线程
                log.debug("{}获取到互斥锁,开启缓存重建线程", id);
                CACHE_THREAD_POOL.submit(() -> {
                    try {
                        //查数据库
                        T data = dbFallback.apply(id);
                        //存入redis
                        this.setWithLogicalExpire(key, data, baseTtl, baseUnit);
                    } catch (Exception e) {
                        log.error("缓存重建线程异常:{}", e.getMessage());
                    } finally {
                        unLock(lockKey);
                    }
                });
                return JSONUtil.toBean((JSONObject) redisData.getData(), type);
            }
            log.debug("{}获取互斥锁失败,返回旧数据", id);
            return JSONUtil.toBean((JSONObject) redisData.getData(), type);
        }
        //不是热点数据,不进行热点数据的相关处理,返回null
        return null;
    }
}
