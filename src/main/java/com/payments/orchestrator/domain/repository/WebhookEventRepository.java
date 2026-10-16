package com.payments.orchestrator.domain.repository;

import com.payments.orchestrator.domain.model.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, String> {
}
