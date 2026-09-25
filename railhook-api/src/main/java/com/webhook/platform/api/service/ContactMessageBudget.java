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

/** A daily ceiling across all senders: the form spends the mail quota verification mails need. */
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
