package com.hmdp.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisConfig {
    @Value("${hm-dp.redis.instance1.host}")
    private String host1;
    @Value("${hm-dp.redis.instance1.port}")
    private int port1;
    @Value("${hm-dp.redis.instance1.password}")
    private String password1;
    @Value("${hm-dp.redis.instance1.database}")
    private int database1;

    @Value("${hm-dp.redis.instance2.host}")
    private String host2;
    @Value("${hm-dp.redis.instance2.port}")
    private int port2;
    @Value("${hm-dp.redis.instance2.password}")
    private String password2;
    @Value("${hm-dp.redis.instance2.database}")
    private int database2;

    @Bean
    public RedissonClient redissonClient1() {
        //redisson配置类
        Config config = new Config();
        //添加redis服务器单点地址
        config.useSingleServer().setAddress("redis://" + host1 + ":" + port1)
                .setPassword(password1)
                .setDatabase(database1);
        //创建redisson客户端
        return Redisson.create(config);
    }

    @Bean
    public RedissonClient redissonClient2() {
        //redisson配置类
        Config config = new Config();
        //添加redis服务器单点地址
        config.useSingleServer().setAddress("redis://" + host2 + ":" + port2)
                .setPassword(password2)
                .setDatabase(database2);
        //创建redisson客户端
        return Redisson.create(config);
    }
}
