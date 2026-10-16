package com.payments.orchestrator.reconciliation;

import java.util.EnumMap;
import java.util.Map;

/** Counts of outcomes for one reconciliation run. */
public final class ReconciliationSummary {

    private final int found;
    private final Map<ReconciliationOutcome, Integer> counts = new EnumMap<>(ReconciliationOutcome.class);

    ReconciliationSummary(int found) {
        this.found = found;
    }

    void record(ReconciliationOutcome outcome) {
        counts.merge(outcome, 1, Integer::sum);
    }

    /** Number of stale PENDING charges examined. */
    public int found() {
        return found;
    }

    public int count(ReconciliationOutcome outcome) {
        return counts.getOrDefault(outcome, 0);
    }

    @Override
    public String toString() {
        return "found=" + found + " " + counts;
    }
}
