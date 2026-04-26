package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result queryTypeList() {
        //查询redis缓存
        String shopTypeKey = RedisConstants.CACHE_SHOP_TYPE_KEY;
        List<String> shopTypeJson = stringRedisTemplate.opsForList().range(shopTypeKey, 0, -1);
        if (shopTypeJson != null && !shopTypeJson.isEmpty()) {
            //命中则返回
            List<ShopType> shopTypes = shopTypeJson.stream().map(json ->
                    JSONUtil.toBean(json, ShopType.class)
            ).collect(Collectors.toList());
            return Result.ok(shopTypes);
        }
        //未命中则查数据库
        List<ShopType> shopTypes = this.query().orderByAsc("sort").list();
        //不存在则返回404
        if (shopTypes == null || shopTypes.isEmpty()) {
            log.warn("数据库:查询店铺类型信息失败");
            return Result.fail("店铺类型信息不存在");
        }
        //写入缓存
        List<String> jsonList = shopTypes.stream().map(JSONUtil::toJsonStr).collect(Collectors.toList());
        stringRedisTemplate.opsForList().rightPushAll(shopTypeKey, jsonList);
        stringRedisTemplate.expire(shopTypeKey, RedisConstants.CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        //返回到前端
        return Result.ok(shopTypes);
    }
}
