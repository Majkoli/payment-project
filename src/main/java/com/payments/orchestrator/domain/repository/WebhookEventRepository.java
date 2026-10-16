package com.payments.orchestrator.domain.repository;

import com.payments.orchestrator.domain.model.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, String> {

    /**
     * Atomically records an event unless its id was already seen ({@code ON CONFLICT DO NOTHING}).
     * If a concurrent transaction is inserting the same id, this blocks until it commits (result 0)
     * or rolls back (result 1), so exactly one transaction ever processes a given event.
     *
     * @return 1 if the event is new and now recorded, 0 if it is a duplicate
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            insert into webhook_events (event_id, event_type, payload, signature)
            values (:eventId, :eventType, :payload, :signature)
            on conflict (event_id) do nothing
            """, nativeQuery = true)
    int insertIfAbsent(@Param("eventId") String eventId,
                       @Param("eventType") String eventType,
                       @Param("payload") String payload,
                       @Param("signature") String signature);
}
