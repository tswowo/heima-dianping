package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.RedissonMultiLock;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.*;

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
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    //秒杀订单队列
    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
    IVoucherOrderService proxy;

    public VoucherOrderServiceImpl(RedisIdWorker redisIdWorker, ISeckillVoucherService iSeckillVoucherService) {
        this.redisIdWorker = redisIdWorker;
        this.iSeckillVoucherService = iSeckillVoucherService;
    }

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    private void handleVoucherOrder(VoucherOrder voucherOrder) throws InterruptedException {
        Long userId = voucherOrder.getUserId();// 获取双 Redis 联锁
        RLock lock1 = redissonClient1.getLock("lock:order:" + userId);
        RLock lock2 = redissonClient2.getLock("lock:order:" + userId);
        RedissonMultiLock multiLock = new RedissonMultiLock(lock1, lock2);
        boolean isLock = multiLock.tryLock(1, 10, TimeUnit.SECONDS);
        if (!isLock) {
            log.error("不允许重复下单");
            return;
        }
        try {
            log.info("获取锁成功，开始创建订单");
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            multiLock.unlock();
        }
    }

    private class VoucherOrderHandle implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    //获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("订单异常:", e);
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandle());
    }


    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userID = UserHolder.getUser().getId();
        long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userID.toString()
        );
        if (result != 0) {
            if (result == 1) {
                log.warn("库存不足");
                return Result.fail("库存不足");
            } else if (result == 2) {
                log.warn("请勿重复下单");
                return Result.fail("请勿重复下单");
            }
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
        //放入队列
        if (!orderTasks.offer(voucherOrder)) {
            log.warn("系统繁忙");
            return Result.fail("系统繁忙");
        }

        proxy = (IVoucherOrderService) AopContext.currentProxy();
        return Result.ok(orderId);
    }

    @Transactional
    @Override
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        //一人一单
        Long userID = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();
        //查询订单
        int count = query().eq("user_id", userID)
                .eq("voucher_id", voucherId)
                .count();
        //判断是否存在
        if (count > 0) {
            log.warn("用户{}重复抢购{}优惠券", userID, voucherId);
            return;
        }
        //扣减库存
        boolean success = iSeckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            log.warn("{}优惠券已售罄", voucherId);
            return;
        }

        save(voucherOrder);
    }

}
