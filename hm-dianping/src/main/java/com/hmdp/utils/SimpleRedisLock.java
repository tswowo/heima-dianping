package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;

import cn.hutool.core.lang.UUID;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private final String name;
    private final StringRedisTemplate stringRedisTemplate;
    private static final String ID_PREFIX = UUID.randomUUID().toString(true)+"-";
    private static final String KEY_PREFIX = "lock:";

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(Long timeoutSec) {
        //获取线程标识
        String threadId = ID_PREFIX+Thread.currentThread().getId();
        //获取锁
        Boolean success=stringRedisTemplate.opsForValue().setIfAbsent(
                KEY_PREFIX + name,
                threadId,
                timeoutSec, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    @Override
    public void unLock() {
        //查看是否是当前线程的锁
        String threadId = ID_PREFIX+Thread.currentThread().getId();
        //获取锁中标识
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        //判断锁是否被其他线程占用
        if(threadId.equals(id)){
            //未被占用,则正常释放锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }
    }
}
