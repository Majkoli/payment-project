package com.payments.orchestrator.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "charges")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Charge {

    @Id
    private UUID id;

    @Column(name = "account_id", nullable = false)
    private String accountId;

    /** Amount in minor units (öre), e.g. 49900 = 499 SEK. */
    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private ChargeStatus status;

    @Setter
    @Column(name = "psp_reference", length = 128, unique = true)
    private String pspReference;

    @Setter
    @Column(name = "failure_reason", columnDefinition = "text")
    private String failureReason;

    /** Optimistic lock; null until first persisted. */
    @Version
    private Integer version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Builder
    private Charge(UUID id, String accountId, long amountMinor, String currency,
                   ChargeStatus status, String pspReference) {
        this.id = id != null ? id : UUID.randomUUID();
        this.accountId = accountId;
        this.amountMinor = amountMinor;
        this.currency = currency != null ? currency : "SEK";
        this.status = status != null ? status : ChargeStatus.PENDING;
        this.pspReference = pspReference;
    }

    public boolean canTransitionTo(ChargeStatus targetStatus) {
        return status.canTransitionTo(targetStatus);
    }

    /**
     * Moves the charge to {@code targetStatus}. Only {@code PaymentStateMachine} should call this;
     * it still guards against illegal transitions as a last line of defence.
     */
    public void changeStatus(ChargeStatus targetStatus) {
        if (!canTransitionTo(targetStatus)) {
            throw new IllegalStateException(
                    "Illegal charge status transition %s -> %s (charge %s)".formatted(status, targetStatus, id));
        }
        this.status = targetStatus;
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
