package com.payments.orchestrator.psp;

public interface PspClient {

    /**
     * Submits a charge to the PSP.
     *
     * @throws PspRejectedException    PSP refused the charge definitively
     * @throws PspUnavailableException PSP unreachable or failed transiently
     */
    PspChargeResult createCharge(PspChargeRequest request);
}
