package com.payments.orchestrator.domain.repository;

import com.payments.orchestrator.domain.model.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, String> {

    /** Removes expired records; returns the number deleted. */
    @Modifying
    @Query("delete from IdempotencyRecord r where r.expiresAt is not null and r.expiresAt < :now")
    int deleteExpired(@Param("now") Instant now);

    /**
     * Atomically claims a key: inserts an IN_PROGRESS record unless one already exists.
     * Uses {@code ON CONFLICT DO NOTHING} (no exception, so the surrounding transaction is not
     * aborted). If a concurrent transaction is inserting the same key, this blocks until it
     * commits or rolls back.
     *
     * @return 1 if this caller won the claim, 0 if the key already exists
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            insert into idempotency_records
                (idempotency_key, request_path, request_hash, status, created_at, expires_at)
            values (:key, :path, :hash, 'IN_PROGRESS', :now, :expiresAt)
            on conflict (idempotency_key) do nothing
            """, nativeQuery = true)
    int tryClaim(@Param("key") String key,
                 @Param("path") String path,
                 @Param("hash") String hash,
                 @Param("now") Instant now,
                 @Param("expiresAt") Instant expiresAt);

    /**
     * Atomically takes over a key whose previous attempt FAILED (retryable) or whose record
     * has expired (a stale IN_PROGRESS lease or an old COMPLETED response). A single conditional
     * UPDATE guarantees only one concurrent caller wins.
     *
     * @return 1 if this caller reclaimed the key, 0 otherwise
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update IdempotencyRecord r
               set r.status = com.payments.orchestrator.domain.model.IdempotencyStatus.IN_PROGRESS,
                   r.requestPath = :path,
                   r.requestHash = :hash,
                   r.responseStatus = null,
                   r.responseBody = null,
                   r.chargeId = null,
                   r.expiresAt = :expiresAt
             where r.idempotencyKey = :key
               and (r.status = com.payments.orchestrator.domain.model.IdempotencyStatus.FAILED
                    or (r.expiresAt is not null and r.expiresAt < :now))
            """)
    int reclaim(@Param("key") String key,
                @Param("path") String path,
                @Param("hash") String hash,
                @Param("now") Instant now,
                @Param("expiresAt") Instant expiresAt);
}
