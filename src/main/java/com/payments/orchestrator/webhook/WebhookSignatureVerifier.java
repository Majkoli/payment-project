package com.payments.orchestrator.webhook;

import com.payments.orchestrator.config.PspProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/** Computes and verifies the HMAC-SHA256 signature of raw webhook bodies. */
@Component
public class WebhookSignatureVerifier {

    private static final String ALGORITHM = "HmacSHA256";
    private static final String PREFIX = "sha256=";

    private final byte[] secret;

    public WebhookSignatureVerifier(PspProperties properties) {
        Assert.hasText(properties.webhook() == null ? null : properties.webhook().secret(),
                "psp.webhook.secret must be configured");
        this.secret = properties.webhook().secret().getBytes(StandardCharsets.UTF_8);
    }

    /** @return lower-case hex HMAC-SHA256 of {@code rawBody} under the webhook secret. */
    public String sign(byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM); // Mac instances are not thread-safe: one per call
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            return HexFormat.of().formatHex(mac.doFinal(rawBody));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    /**
     * Constant-time comparison of the supplied signature against the recomputed one. Accepts hex in
     * any case, optionally prefixed with {@code sha256=}. Missing or blank signatures are invalid.
     */
    public boolean isValid(byte[] rawBody, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }
        String provided = signatureHeader.trim().toLowerCase(Locale.ROOT);
        if (provided.startsWith(PREFIX)) {
            provided = provided.substring(PREFIX.length());
        }
        byte[] expected = sign(rawBody).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, provided.getBytes(StandardCharsets.US_ASCII));
    }
}
