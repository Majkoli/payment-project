package com.payments.orchestrator.psp;

import java.util.UUID;

public interface PspClient {

    /**
     * Submits a charge to the PSP ({@code POST /v1/charges}).
     *
     * @throws PspRejectedException    PSP refused the charge definitively
     * @throws PspUnavailableException PSP unreachable or failed transiently
     */
    PspChargeResponse initiateCharge(UUID chargeId, Long amountMinor, String currency);

    /**
     * Fetches the PSP's current view of a charge ({@code GET /v1/charges/{pspReference}}).
     *
     * @throws PspChargeNotFoundException PSP does not know the reference
     * @throws PspUnavailableException    PSP unreachable or failed transiently
     */
    PspChargeStatusResponse checkChargeStatus(String pspReference);
}
