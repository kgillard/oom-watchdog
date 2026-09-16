package com.trongus.oom.monitor;

import com.trongus.oom.model.JvmSnapshot;

/**
 * SRP / ISP: the sole concern is classifying a raw {@link JvmSnapshot} and
 * returning an enriched copy with the appropriate {@code riskLevel} and
 * updated {@code diagnosisNotes}.
 */
public interface RiskAssessor {

    /**
     * Evaluates the snapshot and returns a new {@link JvmSnapshot} whose
     * {@code riskLevel} and {@code diagnosisNotes} reflect the assessment.
     *
     * @param snapshot a freshly collected snapshot (riskLevel is typically OK)
     * @return an enriched snapshot with the determined risk level
     */
    JvmSnapshot assess(JvmSnapshot snapshot);
}
