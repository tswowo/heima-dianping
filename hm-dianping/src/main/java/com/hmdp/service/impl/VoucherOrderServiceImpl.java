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
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
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
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    private final RedisIdWorker redisIdWorker;
    private final ISeckillVoucherService iSeckillVoucherService;
    @Resource
    private RedissonClient redissonClient1;
    @Resource
    private RedissonClient redissonClient2;

    public VoucherOrderServiceImpl(RedisIdWorker redisIdWorker, ISeckillVoucherService iSeckillVoucherService) {
        this.redisIdWorker = redisIdWorker;
        this.iSeckillVoucherService = iSeckillVoucherService;
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        //查询优惠券信息
        SeckillVoucher voucher = iSeckillVoucherService.getById(voucherId);
        if (voucher == null) {
            log.warn("{}优惠券不存在", voucherId);
            return Result.fail("优惠券不存在");
        }
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
        Long userID = UserHolder.getUser().getId();
        //处理校验
        //先加Redisson分布式锁
        //创建锁对象
        RLock lock1 = redissonClient1.getLock("lock:order:" + userID);
        RLock lock2 = redissonClient2.getLock("lock:order:" + userID);
        RLock multiLock = redissonClient1.getMultiLock(lock1, lock2);
        //获取锁
        boolean isLock;
        try {
            isLock = multiLock.tryLock(1, 10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            log.error("获取锁被中断");
            Thread.currentThread().interrupt();
            return Result.fail("系统繁忙");
        }
        if (!isLock) {
            //获取锁失败,返回失败
            return Result.fail("请勿重复下单");
        }
        //校验并返回处理结果订单id
        try {
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId, userID);
        } finally {
            multiLock.unlock();
        }
    }

    @Override
    @Transactional
    public Result createVoucherOrder(Long voucherId, Long userID) {
        //一人一单
        //查询订单
        int count = query().eq("user_id", userID)
                .eq("voucher_id", voucherId)
                .count();
        //判断是否存在
        if (count > 0) {
            log.warn("用户{}重复抢购{}优惠券", userID, voucherId);
            return Result.fail("用户重复抢购");
        }
        //扣减库存
        boolean success = iSeckillVoucherService.update()
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
        voucherOrder.setUserId(userID);
        //优惠券id
        voucherOrder.setVoucherId(voucherId);

        save(voucherOrder);
        return Result.ok(orderId);
    }

}
