package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisData;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        log.debug("查询店铺id为{}", id);
        Shop shop;

        //互斥锁解决缓存击穿
//        shop = queryByIdWithMutex(id);

        //逻辑过期解决缓存击穿
        shop = cacheClient.queryByIdWithLogicalExpire(RedisConstants.CACHE_SHOP_KEY, RedisConstants.LOCK_SHOP_KEY, id, Shop.class, this::getById
                , RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES
        );
        //缓存穿透
        if (shop == null) {
            //若命中热点数据失败,尝试缓存穿透
            shop = cacheClient.queryByIdWithPassThrough(RedisConstants.CACHE_SHOP_KEY, id, Shop.class, this::getById
                    , RedisConstants.CACHE_SHOP_TTL, TimeUnit.MINUTES
                    , RedisConstants.CACHE_SHOP_RANDOM_TTL, TimeUnit.SECONDS);
        }

        if (shop == null)
            return Result.fail("店铺不存在");
        return Result.ok(shop);
    }

    @Override
    public Shop queryByIdWithMutex(Long id) {
        //先查询redis缓存
        String shopKey = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);

        if (StrUtil.isNotBlank(shopJson)) {
            //命中缓存则返回到前端
            log.debug("{}命中缓存,返回数据:{}", id, shopJson);
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        if (shopJson != null) {
            //若命中为空值，则返回null
            log.warn("{}命中空值缓存,返回空值", id);
            return null;
        }

        //实现缓存重建
        String shopLockKey = RedisConstants.LOCK_SHOP_KEY + id;
        boolean isLock = false;
        try {
            while (true) {
                //获取互斥锁
                isLock = tryLock(shopLockKey);
                //判断是否成功
                if (isLock) {//若成功,再查缓存,再查询数据库
                    log.debug("{}获取互斥锁成功,再次尝试获取缓存", id);
                    shopJson = stringRedisTemplate.opsForValue().get(shopKey);

                    if (StrUtil.isNotBlank(shopJson)) {
                        //命中缓存则返回到前端
                        log.debug("{}再次尝试获取缓存成功,命中缓存,返回数据:{}", id, shopJson);
                        return JSONUtil.toBean(shopJson, Shop.class);
                    }
                    if (shopJson != null) {
                        //若命中为空值，则返回null
                        log.warn("{}再次尝试获取缓存成功,命中空值缓存,返回空值", id);
                        return null;
                    }
                    log.debug("{}再次尝试获取缓存失败,未命中缓存,查询数据库", id);
                    Shop shop = this.getById(id);
                    //若数据库中不存在,返回404
                    if (shop == null) {
                        log.warn("{}不存在于数据库,写入空值缓存,返回空值", id);
                        //将空值写入redis，减轻缓存穿透的影响
                        stringRedisTemplate.opsForValue().set(
                                shopKey
                                , ""
                                , RedisConstants.generateRandomTtlInSeconds(
                                        RedisConstants.CACHE_NULL_TTL
                                        , TimeUnit.MINUTES
                                        , RedisConstants.CACHE_NULL_RANDOM_TTL
                                        , TimeUnit.SECONDS)
                                , TimeUnit.SECONDS);
                        return null;
                    }
                    //从数据库查询到后,写入缓存
                    log.debug("{}存在于数据库,写入缓存,返回数据:{}", id, shop);
                    stringRedisTemplate.opsForValue().set(
                            shopKey
                            , JSONUtil.toJsonStr(shop)
                            , RedisConstants.generateRandomTtlInSeconds(
                                    RedisConstants.CACHE_SHOP_TTL
                                    , TimeUnit.MINUTES
                                    , RedisConstants.CACHE_SHOP_RANDOM_TTL
                                    , TimeUnit.SECONDS)
                            , TimeUnit.SECONDS);
                    //返回结果
                    return shop;
                } else {//若失败,休眠并重试
                    Thread.sleep(50);
                }
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            if (isLock) {
                //释放互斥锁
                log.debug("{}释放互斥锁", id);
                unLock(shopLockKey);
            }
        }
    }


    private boolean tryLock(String key) {
        //setnx,只有redis中不存在key才会成功设置,设置失败说明已经有互斥锁
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10L, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    private void unLock(String key) {
        //删除redis中key的互斥锁
        stringRedisTemplate.delete(key);
    }

    /**
     * 预热热点数据工具函数
     * <p>
     * 逻辑过期解决缓存击穿问题
     * 预热热点数据,储存虚拟过期时间
     */
    public void saveShop2Redis(Long id, Long expireSeconds) throws InterruptedException {
        //查询店铺数据
        Shop shop = this.getById(id);
        if (shop == null) {
            log.warn("{}店铺不存在,预热失败", id);
            return;
        }
        //封装逻辑过期时间
        RedisData redisData = new RedisData(LocalDateTime.now().plusSeconds(expireSeconds), shop);
        //写入redis缓存
        String shopKey = RedisConstants.CACHE_SHOP_KEY + id;
        stringRedisTemplate.opsForValue().set(shopKey, JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result updateWithCache(Shop shop) {
        log.debug("修改店铺id为{}", shop.getId());
        //校验参数
        if (shop.getId() == null) return Result.fail("参数错误");
        //先改数据库
        if (!this.updateById(shop)) {
            log.warn("数据库:修改店铺id为{}失败", shop.getId());
            return Result.fail("修改失败");
        }
        //再删缓存
        String shopKey = RedisConstants.CACHE_SHOP_KEY + shop.getId();

        Boolean deleted = stringRedisTemplate.delete(shopKey);
        log.debug("缓存删除结果: {}, 店铺id: {}", deleted, shop.getId());

        //返回结果
        log.debug("修改店铺id为{}成功", shop.getId());
        return Result.ok();
    }

}
