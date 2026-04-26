package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Override
    public Result queryById(Long id) {
        log.debug("查询店铺id为{}", id);
        //先查询redis缓存
        String shopKey = RedisConstants.CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(shopKey);

        if (StrUtil.isNotBlank(shopJson)) {
            //命中缓存则返回到前端
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            log.debug("缓存命中:查询店铺id为{}成功", id);
            return Result.ok(shop);
        }
        if (shopJson != null) {
            //若命中为空值，则返回错误信息
            log.warn("缓存:查询店铺id为{}失败", id);
            return Result.fail("店铺不存在");
        }
        //未命中则查数据库
        Shop shop = this.getById(id);
        //数据库没有返回404
        if (shop == null) {
            log.warn("数据库:查询店铺id为{}失败", id);
            //将空值写入redis，减轻缓存穿透的影响
            stringRedisTemplate.opsForValue().set(
                    shopKey, "",
                    RedisConstants.generateRandomTtl(RedisConstants.CACHE_NULL_TTL, RedisConstants.CACHE_NULL_RANDOM_TTL), TimeUnit.SECONDS);
            return Result.fail("店铺不存在");
        }
        //写入缓存
        stringRedisTemplate.opsForValue().set(
                shopKey, JSONUtil.toJsonStr(shop),
                RedisConstants.generateRandomTtl(RedisConstants.CACHE_SHOP_TTL, RedisConstants.CACHE_SHOP_RANDOM_TTL), TimeUnit.SECONDS);
        //返回数据到前端
        log.debug("数据库:查询店铺id为{}成功", id);
        return Result.ok(shop);
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
        if (!stringRedisTemplate.delete(shopKey)) {
            log.warn("缓存:删除店铺id为{}失败", shop.getId());
        }
        //返回结果
        log.debug("修改店铺id为{}成功", shop.getId());
        return Result.ok();
    }

}
