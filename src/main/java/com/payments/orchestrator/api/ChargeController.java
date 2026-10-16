package com.payments.orchestrator.api;

import com.payments.orchestrator.api.dto.ChargeResponse;
import com.payments.orchestrator.api.dto.CreateChargeRequest;
import com.payments.orchestrator.idempotency.IdempotencyDecision;
import com.payments.orchestrator.idempotency.IdempotencyManagerService;
import com.payments.orchestrator.service.ChargeOutcome;
import com.payments.orchestrator.service.ChargeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping(ChargeController.PATH)
@RequiredArgsConstructor
public class ChargeController {

    static final String PATH = "/api/v1/charges";
    static final String REPLAYED_HEADER = "Idempotent-Replayed";
    private static final int MAX_KEY_LENGTH = 128;

    private final IdempotencyManagerService idempotency;
    private final ChargeService chargeService;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createCharge(
            @RequestHeader(name = IdempotencyManagerService.HEADER, required = false) String idempotencyKey,
            @Valid @RequestBody CreateChargeRequest request) {

        String key = requireValidKey(idempotencyKey);

        IdempotencyDecision decision = idempotency.begin(key, PATH, request);
        if (decision instanceof IdempotencyDecision.Replay replay) {
            return ResponseEntity.status(replay.httpStatus())
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(REPLAYED_HEADER, "true")
                    .body(replay.responseBody());
        }

        ChargeOutcome outcome;
        try {
            outcome = chargeService.createCharge(request);
        } catch (RuntimeException e) {
            idempotency.markFailed(key);
            throw e;
        }

        idempotency.finalizeIdempotency(key, outcome.httpStatus(), outcome.body(), outcome.body().id());
        return ResponseEntity.status(outcome.httpStatus())
                .contentType(MediaType.APPLICATION_JSON)
                .body(outcome.body());
    }

    @GetMapping(path = "/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ChargeResponse> getCharge(@PathVariable UUID id) {
        return ResponseEntity.ok(chargeService.getCharge(id));
    }

    private static String requireValidKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Missing required header: " + IdempotencyManagerService.HEADER);
        }
        String trimmed = key.trim();
        if (trimmed.length() > MAX_KEY_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    IdempotencyManagerService.HEADER + " must be at most " + MAX_KEY_LENGTH + " characters");
        }
        return trimmed;
    }
}
