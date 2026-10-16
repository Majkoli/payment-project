package com.payments.orchestrator.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.springframework.data.domain.Persistable;

import java.sql.Types;
import java.time.Instant;
import java.util.UUID;

/**
 * Implements {@link Persistable} so {@code save()} always issues an INSERT for a new key
 * (instead of Spring Data's select-then-merge), letting the primary key act as the
 * atomic "claim" for an idempotency key.
 */
@Entity
@Table(name = "idempotency_records")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecord implements Persistable<String> {

    @Id
    @Column(name = "idempotency_key", length = 128)
    private String idempotencyKey;

    @Column(name = "request_path")
    private String requestPath;

    /** SHA-256 hex digest of the request (64 chars). */
    @JdbcTypeCode(Types.CHAR)
    @Column(name = "request_hash", length = 64)
    private String requestHash;

    @Setter
    @Column(name = "response_status")
    private Integer responseStatus;

    @Setter
    @Column(name = "response_body", columnDefinition = "text")
    private String responseBody;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private IdempotencyStatus status;

    @Setter
    @Column(name = "charge_id")
    private UUID chargeId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Transient
    private boolean isNew = true;

    @Builder
    private IdempotencyRecord(String idempotencyKey, String requestPath, String requestHash,
                              Integer responseStatus, String responseBody, IdempotencyStatus status,
                              UUID chargeId, Instant expiresAt) {
        this.idempotencyKey = idempotencyKey;
        this.requestPath = requestPath;
        this.requestHash = requestHash;
        this.responseStatus = responseStatus;
        this.responseBody = responseBody;
        this.status = status != null ? status : IdempotencyStatus.IN_PROGRESS;
        this.chargeId = chargeId;
        this.expiresAt = expiresAt;
    }

    @Override
    public String getId() {
        return idempotencyKey;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
