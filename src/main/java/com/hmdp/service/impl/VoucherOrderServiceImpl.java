package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;


@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ISeckillVoucherService seckillVoucherService;

    @Autowired
    private RedisIdWorker redisIdWorker;

    /**
     * * 秒杀优惠卷
     *
     * @param voucherId
     * @return
     */
    @Override
    @Transactional
    public Result seckillVoucher(Long voucherId) {
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀未开始");
        }
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已结束");
        }
        if (seckillVoucher.getStock() < 1) {
            return Result.fail("库存不足");
        }
        Long uid = UserHolder.getUser().getId();

        SimpleRedisLock lock = new SimpleRedisLock("order:" + uid, redisTemplate);
        if (!lock.tryLock(1200)) {
            return Result.fail("请勿重复下单");
        }

        int count = query().eq("user_id", uid).eq("voucher_id", voucherId).count().intValue();
        if (count > 0) {
            return Result.fail("用户已经购买过一次！");
        }
        long orderId;
        try {
            boolean success = seckillVoucherService.update()
                    .setSql("stock= stock -1")
                    .eq("voucher_id", voucherId)
                    .gt("stock", 0)
                    .update();
            if(!success){
                return Result.fail("库存不足");
            }

            VoucherOrder voucherOrder = new VoucherOrder();
            orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            voucherOrder.setVoucherId(voucherId);
            voucherOrder.setUserId(uid);
            save(voucherOrder);
        } finally {
            lock.unlock();
        }

        return Result.ok(orderId);
    }
}
