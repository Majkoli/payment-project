package com.payments.orchestrator.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payments.orchestrator.config.IdempotencyProperties;
import com.payments.orchestrator.domain.model.IdempotencyRecord;
import com.payments.orchestrator.domain.model.IdempotencyStatus;
import com.payments.orchestrator.domain.repository.IdempotencyRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Idempotency engine backed by PostgreSQL with an optional short-lived Redis lock (`SETNX` with
 * 10s TTL on {@code lock:idempotency:{key}}) as a fast first-line concurrency guard.
 *
 * <p>PostgreSQL's primary key on {@code idempotency_key} remains the durable source of truth:
 * even if Redis is unreachable, {@code INSERT ... ON CONFLICT DO NOTHING} guarantees that at most
 * one concurrent caller claims the key and proceeds.
 *
 * <p>Methods run in their own short transactions and must NOT be wrapped in a caller transaction
 * that spans the downstream PSP call: the IN_PROGRESS claim has to be committed and visible to
 * concurrent requests while processing is underway.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyManagerService {

    public static final String HEADER = "Idempotency-Key";
    static final String LOCK_KEY_PREFIX = "lock:idempotency:";
    static final Duration REDIS_LOCK_TTL = Duration.ofSeconds(10);

    /** Bounded retries for the rare case where a row is deleted between claim and read. */
    private static final int MAX_ATTEMPTS = 3;

    private final IdempotencyRecordRepository repository;
    private final IdempotencyProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final StringRedisTemplate redisTemplate;

    /**
     * Claims {@code key} for the given request or resolves what to do with an existing record.
     *
     * @throws IdempotencyKeyReusedException (422) key exists for a different payload/path
     * @throws IdempotencyConflictException  (409) key exists and is still IN_PROGRESS
     */
    @Transactional
    public IdempotencyDecision begin(String key, String requestPath, Object requestBody) {
        String hash = RequestHasher.computeSha256(requestBody);
        Instant now = clock.instant();
        Instant lease = now.plus(properties.inProgressTimeout());

        // Fast-path: check if a completed record already exists before contending on locks
        Optional<IdempotencyRecord> preExisting = repository.findById(key);
        if (preExisting.isPresent() && !preExisting.get().isExpired(now)
                && preExisting.get().getStatus() != IdempotencyStatus.FAILED) {
            return resolveExisting(preExisting.get(), requestPath, hash);
        }

        // Acquire short-lived Redis lock (SETNX with 10s TTL on lock:idempotency:{key}) if Redis is up
        if (!tryAcquireRedisLock(key)) {
            throw new IdempotencyConflictException();
        }

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            if (repository.tryClaim(key, requestPath, hash, now, lease) == 1
                    || repository.reclaim(key, requestPath, hash, now, lease) == 1) {
                return new IdempotencyDecision.Proceed();
            }
            Optional<IdempotencyRecord> existing = repository.findById(key);
            if (existing.isPresent()) {
                return resolveExisting(existing.get(), requestPath, hash);
            }
        }
        throw new IdempotencyConflictException();
    }

    private IdempotencyDecision resolveExisting(IdempotencyRecord record, String requestPath, String hash) {
        if (!requestPath.equals(record.getRequestPath()) || !hash.equals(record.getRequestHash())) {
            throw new IdempotencyKeyReusedException();
        }
        if (record.getStatus() == IdempotencyStatus.COMPLETED) {
            return new IdempotencyDecision.Replay(record.getResponseStatus(), record.getResponseBody());
        }
        // IN_PROGRESS, or FAILED/expired that another caller reclaimed first.
        throw new IdempotencyConflictException();
    }

    /** Stores the response, marks the key COMPLETED (24h TTL), and releases the short-lived Redis lock. */
    @Transactional
    public void finalizeIdempotency(String key, int httpStatus, Object responseBody, UUID chargeId) {
        try {
            IdempotencyRecord record = repository.findById(key)
                    .orElseThrow(() -> new IllegalStateException("No idempotency record for key " + key));
            record.setResponseStatus(httpStatus);
            record.setResponseBody(serialize(responseBody));
            record.setChargeId(chargeId);
            record.setStatus(IdempotencyStatus.COMPLETED);
            record.setExpiresAt(clock.instant().plus(properties.ttl()));
        } finally {
            releaseRedisLock(key);
        }
    }

    /**
     * Releases the key after a technical failure (nothing is cached) so the client can retry with
     * the same key. Safe to call when the record is missing.
     */
    @Transactional
    public void markFailed(String key) {
        try {
            repository.findById(key).ifPresent(record -> record.setStatus(IdempotencyStatus.FAILED));
        } finally {
            releaseRedisLock(key);
        }
    }

    private boolean tryAcquireRedisLock(String key) {
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(LOCK_KEY_PREFIX + key, "1", REDIS_LOCK_TTL);
            return !Boolean.FALSE.equals(acquired);
        } catch (RuntimeException e) {
            log.debug("Redis unavailable for idempotency lock '{}'; falling back to PostgreSQL constraint", key);
            return true;
        }
    }

    private void releaseRedisLock(String key) {
        try {
            redisTemplate.delete(LOCK_KEY_PREFIX + key);
        } catch (RuntimeException e) {
            log.debug("Redis unavailable when releasing idempotency lock '{}'", key);
        }
    }

    private String serialize(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize response body for idempotency cache", e);
        }
    }
}
