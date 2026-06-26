package com.oms.order.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.oms.order.domain.Order;
import com.oms.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Three-tier cache for orders:
 *
 *   L1 — Caffeine JVM cache  (nanoseconds, per pod, lost on restart)
 *   L2 — Redis               (microseconds, shared across pods, survives restart)
 *   L3 — PostgreSQL          (milliseconds, source of truth, never expires)
 *
 * Read strategy — read through:
 *   Check L1 → miss → check L2 → miss → read L3 → backfill L2 and L1
 *
 * Write strategy — write through:
 *   Write L3 first (source of truth) → write L2 → write L1
 *
 * Invalidation — on any update or cancel:
 *   Remove from L1 and L2 immediately so next read gets fresh data from L3
 */
@Component
public class OrderCacheManager {

    private static final Logger log = LoggerFactory.getLogger(OrderCacheManager.class);

    private static final String REDIS_KEY_PREFIX = "order:";
    private static final Duration L2_TTL = Duration.ofMinutes(10);

    // L1 — JVM cache
    // 500 orders max — prevents JVM heap pressure
    // 30 second TTL — short enough to prevent stale reads, long enough to absorb bursts
    private final Cache<UUID, Order> l1Cache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(30, TimeUnit.SECONDS)
            .build();

    // Mutex locks per order ID — prevents cache stampede when a popular order expires
    // computeIfAbsent ensures only one lock object is created per orderId atomically
    private final ConcurrentHashMap<UUID, Object> stampedeLocks = new ConcurrentHashMap<>();

    private final RedisTemplate<String, Order> redisTemplate;
    private final OrderRepository orderRepository;

    public OrderCacheManager(RedisTemplate<String, Order> redisTemplate,
                             OrderRepository orderRepository) {
        this.redisTemplate = redisTemplate;
        this.orderRepository = orderRepository;
    }

    /**
     * Read through all three tiers.
     * Each tier only queried if the one above it misses.
     * On miss at any tier, the result is backfilled upward.
     */
    public Optional<Order> getOrder(UUID orderId) {

        // ── L1: JVM Cache ────────────────────────────────────────────────
        Order l1Hit = l1Cache.getIfPresent(orderId);
        if (l1Hit != null) {
            log.debug("L1 cache hit orderId={}", orderId);
            return Optional.of(l1Hit);
        }

        // ── L2: Redis ────────────────────────────────────────────────────
        String redisKey = REDIS_KEY_PREFIX + orderId;
        Order l2Hit = redisTemplate.opsForValue().get(redisKey);
        if (l2Hit != null) {
            log.debug("L2 cache hit orderId={}", orderId);
            // backfill L1 — next request served from JVM, no Redis call
            l1Cache.put(orderId, l2Hit);
            return Optional.of(l2Hit);
        }

        // ── L3: Database (with stampede protection) ───────────────────────
        // Multiple threads may reach here simultaneously for the same orderId
        // when the cache expires under high load — stampede protection ensures
        // only one thread queries the database, others wait and use the result
        Object lock = stampedeLocks.computeIfAbsent(orderId, k -> new Object());

        synchronized (lock) {

            // Double check — another thread may have already populated L2
            // while this thread was waiting for the lock
            Order doubleCheck = redisTemplate.opsForValue().get(redisKey);
            if (doubleCheck != null) {
                log.debug("L2 cache hit after lock wait orderId={}", orderId);
                l1Cache.put(orderId, doubleCheck);
                return Optional.of(doubleCheck);
            }

            // We are the one thread that queries the database
            log.debug("L3 database hit orderId={}", orderId);
            Optional<Order> dbResult = orderRepository.findById(orderId);

            dbResult.ifPresent(order -> {
                // backfill L2 then L1 on the way back up
                redisTemplate.opsForValue().set(redisKey, order, L2_TTL);
                l1Cache.put(orderId, order);
            });

            return dbResult;
        }
    }

    /**
     * Write through all three tiers.
     * Always writes L3 first — database is the source of truth.
     * L2 and L1 updated after to keep all tiers consistent.
     */
    public Order saveOrder(Order order) {

        // L3 — database first, always
        Order saved = orderRepository.save(order);

        // L2 — Redis with TTL
        String redisKey = REDIS_KEY_PREFIX + saved.getId();
        redisTemplate.opsForValue().set(redisKey, saved, L2_TTL);

        // L1 — JVM cache
        l1Cache.put(saved.getId(), saved);

        log.debug("Written through all cache tiers orderId={}", saved.getId());
        return saved;
    }

    /**
     * Invalidate across all tiers when an order changes (cancel, status update).
     *
     * Evicts from L1 and L2 immediately — next read rebuilds from L3 database.
     * No need to update L3 here — the caller already updated the database
     * before calling invalidate.
     */
    public void invalidate(UUID orderId) {
        l1Cache.invalidate(orderId);
        redisTemplate.delete(REDIS_KEY_PREFIX + orderId);
        log.debug("Invalidated all cache tiers orderId={}", orderId);
    }
}
