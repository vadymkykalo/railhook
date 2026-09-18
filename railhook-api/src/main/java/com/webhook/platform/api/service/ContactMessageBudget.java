package com.webhook.platform.api.service;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * How many messages the public contact form may send today, across every sender.
 *
 * <p>The per-address limit stops one sender; it does not stop a thousand addresses. Every message
 * spends the same mail-provider quota that verification and password-reset mails need, so the form
 * gets a daily ceiling of its own and refuses past it until the next UTC day. The count lives in
 * Redis so every API instance shares it; with Redis down each instance keeps its own, which still
 * bounds the total by the ceiling times the instance count.
 */
@Service
@Slf4j
public class ContactMessageBudget {

    private static final String KEY_PREFIX = "contact:daily:";

    private final RedissonClient redissonClient;
    private final int dailyLimit;
    private final Clock clock;
    private final AtomicReference<LocalDate> localDay = new AtomicReference<>();
    private final AtomicLong localCount = new AtomicLong();

    @Autowired
    public ContactMessageBudget(RedissonClient redissonClient,
                                @Value("${app.contact.daily-limit:30}") int dailyLimit) {
        this(redissonClient, dailyLimit, Clock.systemUTC());
    }

    ContactMessageBudget(RedissonClient redissonClient, int dailyLimit, Clock clock) {
        this.redissonClient = redissonClient;
        this.dailyLimit = dailyLimit;
        this.clock = clock;
    }

    /** Takes one message from today's budget; false once it is spent. */
    public boolean tryAcquire() {
        LocalDate today = LocalDate.now(clock);
        long used;
        try {
            RAtomicLong counter = redissonClient.getAtomicLong(KEY_PREFIX + today);
            used = counter.incrementAndGet();
            if (used == 1) {
                counter.expire(Duration.ofDays(2));
            }
        } catch (Exception e) {
            log.warn("Contact budget counter unavailable, counting locally: {}", e.getMessage());
            if (!today.equals(localDay.getAndSet(today))) {
                localCount.set(0);
            }
            used = localCount.incrementAndGet();
        }
        if (used > dailyLimit) {
            log.warn("Contact form daily ceiling of {} reached", dailyLimit);
            return false;
        }
        return true;
    }
}
