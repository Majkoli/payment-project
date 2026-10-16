package com.payments.orchestrator.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payments.orchestrator.domain.exception.IllegalStateTransitionException;
import com.payments.orchestrator.domain.model.Charge;
import com.payments.orchestrator.domain.model.PaymentEvent;
import com.payments.orchestrator.domain.repository.ChargeRepository;
import com.payments.orchestrator.domain.repository.WebhookEventRepository;
import com.payments.orchestrator.domain.service.PaymentStateMachine;
import com.payments.orchestrator.domain.service.PaymentStateMachine.TransitionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Authenticates, deduplicates and applies PSP webhooks.
 *
 * <ol>
 *   <li><b>Verify</b> the HMAC over the raw bytes <i>before</i> parsing anything (401 on mismatch).</li>
 *   <li>In ONE transaction: record the event id (insert-if-absent: duplicates stop here), lock the
 *       charge row ({@code SELECT ... FOR UPDATE}), apply the transition, commit.</li>
 * </ol>
 * Event recording and the charge update share a transaction on purpose: if the charge cannot be found
 * the whole transaction rolls back, the event is NOT recorded, and the PSP's retry is processed
 * normally instead of being swallowed as a duplicate.
 *
 * <p>Events that are authentic but cannot change anything (an illegal transition from a terminal
 * state, an unknown event type, an amount mismatch) are recorded and acknowledged with 200: a retry
 * could never succeed, so asking the PSP to redeliver would only create noise. They are logged.
 */
@Slf4j
@Service
public class WebhookService {

    private static final int MAX_EVENT_ID_LENGTH = 128;
    private static final int MAX_EVENT_TYPE_LENGTH = 64;

    private final WebhookSignatureVerifier signatureVerifier;
    private final WebhookEventRepository webhookEventRepository;
    private final ChargeRepository chargeRepository;
    private final PaymentStateMachine stateMachine;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public WebhookService(WebhookSignatureVerifier signatureVerifier,
                          WebhookEventRepository webhookEventRepository,
                          ChargeRepository chargeRepository,
                          PaymentStateMachine stateMachine,
                          TransactionTemplate transactionTemplate,
                          ObjectMapper objectMapper) {
        this.signatureVerifier = signatureVerifier;
        this.webhookEventRepository = webhookEventRepository;
        this.chargeRepository = chargeRepository;
        this.stateMachine = stateMachine;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * @param rawBody   the exact bytes received (the signature covers them, not a re-serialization)
     * @param signature value of the {@code X-Signature-SHA256} header, may be null
     * @throws InvalidWebhookSignatureException (401) bad or missing signature
     * @throws ResponseStatusException          (400) authentic but malformed payload
     * @throws WebhookChargeNotFoundException   (404) unknown PSP reference; nothing was recorded
     */
    public WebhookResult process(byte[] rawBody, String signature) {
        if (!signatureVerifier.isValid(rawBody, signature)) {
            throw new InvalidWebhookSignatureException();
        }
        PspWebhookPayload payload = parseAndValidate(rawBody);
        return transactionTemplate.execute(status -> handle(payload, rawBody, signature.trim()));
    }

    private WebhookResult handle(PspWebhookPayload payload, byte[] rawBody, String signature) {
        int inserted = webhookEventRepository.insertIfAbsent(
                payload.eventId(), payload.eventType(), new String(rawBody, StandardCharsets.UTF_8), signature);
        if (inserted == 0) {
            log.info("Duplicate webhook event {} ignored", payload.eventId());
            return WebhookResult.DUPLICATE;
        }

        Optional<PaymentEvent> event = PaymentEvent.fromWireType(payload.eventType());
        if (event.isEmpty()) {
            log.info("Webhook event {} has unhandled type {}; acknowledged", payload.eventId(), payload.eventType());
            return WebhookResult.IGNORED;
        }

        Charge charge = chargeRepository.findByPspReferenceForUpdate(payload.pspReference())
                .orElseThrow(() -> new WebhookChargeNotFoundException(payload.pspReference()));

        if (payload.amountMinor() != null && payload.amountMinor() != charge.getAmountMinor()) {
            log.error("Webhook event {} amount {} does not match charge {} amount {}; not applied",
                    payload.eventId(), payload.amountMinor(), charge.getId(), charge.getAmountMinor());
            return WebhookResult.IGNORED;
        }

        try {
            TransitionResult result = stateMachine.apply(charge, event.get());
            if (result == TransitionResult.NO_OP) {
                log.info("Webhook event {} is a no-op for charge {} in status {}",
                        payload.eventId(), charge.getId(), charge.getStatus());
                return WebhookResult.IGNORED;
            }
            if (event.get() == PaymentEvent.CHARGE_FAILED) {
                charge.setFailureReason(payload.failureReason());
            }
            return WebhookResult.PROCESSED; // dirty checking flushes the charge on commit
        } catch (IllegalStateTransitionException e) {
            log.warn("Webhook event {} rejected for charge {}: {}", payload.eventId(), charge.getId(), e.getMessage());
            return WebhookResult.IGNORED;
        }
    }

    private PspWebhookPayload parseAndValidate(byte[] rawBody) {
        PspWebhookPayload payload;
        try {
            payload = objectMapper.readValue(rawBody, PspWebhookPayload.class);
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Webhook body is not valid JSON.");
        }
        if (payload == null
                || isBlank(payload.eventId()) || payload.eventId().length() > MAX_EVENT_ID_LENGTH
                || isBlank(payload.eventType()) || payload.eventType().length() > MAX_EVENT_TYPE_LENGTH
                || isBlank(payload.pspReference())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Webhook requires eventId (max 128 chars), eventType (max 64 chars) and pspReference.");
        }
        return payload;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
