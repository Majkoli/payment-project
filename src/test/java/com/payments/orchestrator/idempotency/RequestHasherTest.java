package com.payments.orchestrator.idempotency;

import com.payments.orchestrator.api.dto.CreateChargeRequest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RequestHasherTest {

    @Test
    void producesSixtyFourCharLowerCaseHex() {
        String hash = RequestHasher.computeSha256(new CreateChargeRequest("acc_1", 49_900L, "SEK"));

        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void isDeterministicForEqualPayloads() {
        assertThat(RequestHasher.computeSha256(new CreateChargeRequest("acc_1", 49_900L, "SEK")))
                .isEqualTo(RequestHasher.computeSha256(new CreateChargeRequest("acc_1", 49_900L, "SEK")));
    }

    @Test
    void omittedCurrencyHashesLikeExplicitSek() {
        assertThat(RequestHasher.computeSha256(new CreateChargeRequest("acc_1", 100L, null)))
                .isEqualTo(RequestHasher.computeSha256(new CreateChargeRequest("acc_1", 100L, "sek")));
    }

    @Test
    void differsWhenAnyFieldChanges() {
        String base = RequestHasher.computeSha256(new CreateChargeRequest("acc_1", 49_900L, "SEK"));

        assertThat(RequestHasher.computeSha256(new CreateChargeRequest("acc_2", 49_900L, "SEK"))).isNotEqualTo(base);
        assertThat(RequestHasher.computeSha256(new CreateChargeRequest("acc_1", 49_901L, "SEK"))).isNotEqualTo(base);
        assertThat(RequestHasher.computeSha256(new CreateChargeRequest("acc_1", 49_900L, "EUR"))).isNotEqualTo(base);
    }

    @Test
    void mapKeyOrderDoesNotAffectHash() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("x", 1);
        a.put("y", 2);
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("y", 2);
        b.put("x", 1);

        assertThat(RequestHasher.computeSha256(a)).isEqualTo(RequestHasher.computeSha256(b));
    }
}
