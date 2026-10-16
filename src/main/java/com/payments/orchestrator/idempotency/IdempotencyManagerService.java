package com.payments.orchestrator.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payments.orchestrator.config.IdempotencyProperties;
import com.payments.orchestrator.domain.model.IdempotencyRecord;
import com.payments.orchestrator.domain.model.IdempotencyStatus;
import com.payments.orchestrator.domain.repository.IdempotencyRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Idempotency engine backed by PostgreSQL. The primary key on {@code idempotency_key} is the
 * atomic claim: exactly one concurrent caller inserts the IN_PROGRESS row and proceeds, the rest
 * observe it and are told to replay (COMPLETED) or retry later (409).
 *
 * <p>Methods run in their own short transactions and must NOT be wrapped in a caller transaction
 * that spans the downstream PSP call: the IN_PROGRESS claim has to be committed and visible to
 * concurrent requests while processing is underway.
 */
@Service
@RequiredArgsConstructor
public class IdempotencyManagerService {

    public static final String HEADER = "Idempotency-Key";

    /** Bounded retries for the rare case where a row is deleted between claim and read. */
    private static final int MAX_ATTEMPTS = 3;

    private final IdempotencyRecordRepository repository;
    private final IdempotencyProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

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

    /** Stores the response and marks the key COMPLETED, replayable for the configured TTL (24h). */
    @Transactional
    public void finalizeIdempotency(String key, int httpStatus, Object responseBody, UUID chargeId) {
        IdempotencyRecord record = repository.findById(key)
                .orElseThrow(() -> new IllegalStateException("No idempotency record for key " + key));
        record.setResponseStatus(httpStatus);
        record.setResponseBody(serialize(responseBody));
        record.setChargeId(chargeId);
        record.setStatus(IdempotencyStatus.COMPLETED);
        record.setExpiresAt(clock.instant().plus(properties.ttl()));
    }

    /**
     * Releases the key after a technical failure (nothing is cached) so the client can retry with
     * the same key. Safe to call when the record is missing.
     */
    @Transactional
    public void markFailed(String key) {
        repository.findById(key).ifPresent(record -> record.setStatus(IdempotencyStatus.FAILED));
    }

    private String serialize(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize response body for idempotency cache", e);
        }
    }
}
