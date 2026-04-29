package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private final RedisIdWorker redisIdWorker;
    private final StringRedisTemplate stringRedisTemplate;
    private final ISeckillVoucherService iSeckillVoucherService;

    public VoucherOrderServiceImpl(RedisIdWorker redisIdWorker, StringRedisTemplate stringRedisTemplate, ISeckillVoucherService iSeckillVoucherService) {
        this.redisIdWorker = redisIdWorker;
        this.stringRedisTemplate = stringRedisTemplate;
        this.iSeckillVoucherService = iSeckillVoucherService;
    }

    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {
        //查询优惠券信息
        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);
        //判断是否在秒杀时间段内
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            log.warn("{}优惠券抢购尚未开始", voucherId);
            return Result.fail("优惠券抢购尚未开始");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            log.warn("{}优惠券抢购已结束", voucherId);
            return Result.fail("优惠券抢购已结束");
        }
        //判断库存状态
        if (voucher.getStock() < 1) {
            log.warn("{}优惠券已售罄", voucherId);
            return Result.fail("优惠券已售罄");
        }
        //扣减库存
         boolean success =iSeckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            log.warn("{}优惠券已售罄", voucherId);
            return Result.fail("优惠券已售罄");
        }
        //创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //订单id
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        //用户id
        long userID = UserHolder.getUser().getId();
        voucherOrder.setUserId(userID);
        //优惠券id
        voucherOrder.setVoucherId(voucherId);

        save(voucherOrder);
        //返回订单id
        return Result.ok(orderId);
    }

}
