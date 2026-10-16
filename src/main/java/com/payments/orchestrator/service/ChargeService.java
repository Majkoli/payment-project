package com.payments.orchestrator.service;

import com.payments.orchestrator.api.dto.ChargeResponse;
import com.payments.orchestrator.api.dto.CreateChargeRequest;
import com.payments.orchestrator.domain.model.Charge;
import com.payments.orchestrator.domain.model.PaymentEvent;
import com.payments.orchestrator.domain.repository.ChargeRepository;
import com.payments.orchestrator.domain.service.PaymentStateMachine;
import com.payments.orchestrator.psp.PspChargeResponse;
import com.payments.orchestrator.psp.PspClient;
import com.payments.orchestrator.psp.PspRejectedException;
import com.payments.orchestrator.psp.PspUnavailableException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

/**
 * Creates a charge and submits it to the PSP. Deliberately not {@code @Transactional}: the PSP
 * call is slow network I/O and must not hold a database connection or transaction open, so each
 * persistence step runs in its own short transaction via {@link TransactionTemplate}.
 *
 * <p>Outcomes:
 * <ul>
 *   <li>PSP accepts: charge stays PENDING with its PSP reference (201); the webhook settles it.</li>
 *   <li>PSP refuses (4xx): charge becomes FAILED, returned as 402; deterministic, so cacheable.</li>
 *   <li>PSP unavailable: charge becomes FAILED and {@link PspUnavailableException} propagates (502);
 *       not cached, the client may retry with the same key.</li>
 * </ul>
 */
@Slf4j
@Service
public class ChargeService {

    private final ChargeRepository chargeRepository;
    private final PspClient pspClient;
    private final PaymentStateMachine stateMachine;
    private final TransactionTemplate transactionTemplate;

    public ChargeService(ChargeRepository chargeRepository, PspClient pspClient,
                         PaymentStateMachine stateMachine, TransactionTemplate transactionTemplate) {
        this.chargeRepository = chargeRepository;
        this.pspClient = pspClient;
        this.stateMachine = stateMachine;
        this.transactionTemplate = transactionTemplate;
    }

    public ChargeOutcome createCharge(CreateChargeRequest request) {
        Charge charge = transactionTemplate.execute(status -> chargeRepository.save(Charge.builder()
                .accountId(request.accountId())
                .amountMinor(request.amountMinor())
                .currency(request.currency())
                .build()));

        try {
            PspChargeResponse result = pspClient.initiateCharge(
                    charge.getId(), charge.getAmountMinor(), charge.getCurrency());
            Charge submitted = update(charge.getId(), c -> c.setPspReference(result.pspReference()));
            return new ChargeOutcome(HttpStatus.CREATED.value(), ChargeResponse.from(submitted));
        } catch (PspRejectedException e) {
            log.warn("PSP rejected charge {}: {}", charge.getId(), e.getMessage());
            Charge failed = fail(charge.getId(), e.getMessage());
            return new ChargeOutcome(HttpStatus.PAYMENT_REQUIRED.value(), ChargeResponse.from(failed));
        } catch (PspUnavailableException e) {
            log.error("PSP unavailable for charge {}", charge.getId(), e);
            fail(charge.getId(), e.getMessage());
            throw e;
        }
    }

    public ChargeResponse getCharge(UUID chargeId) {
        return transactionTemplate.execute(status -> chargeRepository.findById(chargeId)
                .map(ChargeResponse::from)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Charge not found: " + chargeId)));
    }

    private Charge fail(UUID chargeId, String reason) {
        return update(chargeId, c -> {
            stateMachine.apply(c, PaymentEvent.CHARGE_FAILED);
            c.setFailureReason(reason);
        });
    }

    private Charge update(UUID chargeId, java.util.function.Consumer<Charge> change) {
        return transactionTemplate.execute(status -> {
            Charge charge = chargeRepository.findById(chargeId).orElseThrow();
            change.accept(charge);
            return charge;
        });
    }
}
