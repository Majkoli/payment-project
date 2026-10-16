package com.payments.orchestrator.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * Processed PSP webhook, keyed by the PSP's event id for deduplication. Implements
 * {@link Persistable} so a duplicate event id fails with a constraint violation on INSERT
 * rather than silently overwriting the stored row via merge.
 */
@Entity
@Table(name = "webhook_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WebhookEvent implements Persistable<String> {

    @Id
    @Column(name = "event_id", length = 128)
    private String eventId;

    @Column(name = "event_type", length = 64)
    private String eventType;

    @Column(columnDefinition = "text")
    private String payload;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @Column(length = 256)
    private String signature;

    @Transient
    private boolean isNew = true;

    @Builder
    private WebhookEvent(String eventId, String eventType, String payload, String signature) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.payload = payload;
        this.signature = signature;
    }

    @Override
    public String getId() {
        return eventId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PrePersist
    void onCreate() {
        if (processedAt == null) {
            processedAt = Instant.now();
        }
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }
}
