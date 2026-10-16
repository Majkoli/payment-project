package com.payments.orchestrator.webhook;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/webhooks")
@RequiredArgsConstructor
public class WebhookController {

    static final String SIGNATURE_HEADER = "X-Signature-SHA256";

    private final WebhookService webhookService;

    /**
     * Takes the body as {@code byte[]} so the HMAC is computed over exactly what the PSP signed;
     * a parsed-and-reserialized body would not verify reliably.
     */
    @PostMapping(path = "/psp", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> receivePspWebhook(
            @RequestBody byte[] rawBody,
            @RequestHeader(name = SIGNATURE_HEADER, required = false) String signature) {
        WebhookResult result = webhookService.process(rawBody, signature);
        return ResponseEntity.ok(Map.of("status", result.name()));
    }
}
