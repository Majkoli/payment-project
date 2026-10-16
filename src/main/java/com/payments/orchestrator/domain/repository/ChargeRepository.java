package com.payments.orchestrator.domain.repository;

import com.payments.orchestrator.domain.model.Charge;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChargeRepository extends JpaRepository<Charge, UUID> {

    Optional<Charge> findByPspReference(String pspReference);

    /**
     * Loads the charge with {@code SELECT ... FOR UPDATE} so concurrent webhook deliveries for the
     * same PSP reference are serialized. Must be called inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Charge c where c.pspReference = :pspReference")
    Optional<Charge> findByPspReferenceForUpdate(@Param("pspReference") String pspReference);

    /**
     * Loads the charge by id with {@code SELECT ... FOR UPDATE}. Used when the PSP reference may be
     * absent (reconciliation). Must be called inside a transaction.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Charge c where c.id = :id")
    Optional<Charge> findByIdForUpdate(@Param("id") UUID id);

    /** PENDING charges created strictly before {@code cutoffTime}, oldest first. */
    @Query("""
            select c from Charge c
            where c.status = com.payments.orchestrator.domain.model.ChargeStatus.PENDING
              and c.createdAt < :cutoffTime
            order by c.createdAt asc
            """)
    List<Charge> findStalePendingCharges(@Param("cutoffTime") Instant cutoffTime);
}
