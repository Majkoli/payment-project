package com.payments.orchestrator.psp;

/** The PSP has no charge with the requested reference (HTTP 404). */
public class PspChargeNotFoundException extends RuntimeException {

    public PspChargeNotFoundException(String pspReference) {
        super("PSP has no charge with reference " + pspReference);
    }
}
