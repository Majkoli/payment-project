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
}
