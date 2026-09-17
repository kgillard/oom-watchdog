package com.ibm.oomwatchdog.model;

/**
 * Severity levels for an OOM risk assessment, ordered from benign to fatal.
 * Consumers can compare with ordinal() to check "at least WARNING" etc.
 */
public enum OomRiskLevel {

    /** Everything is healthy – heap well below warning threshold. */
    OK,

    /** Heap is approaching the warning threshold; a trend is developing. */
    WARNING,

    /** Heap is at or above the critical threshold; OOM is imminent. */
    CRITICAL,

    /** An OutOfMemoryError has already been thrown or is actively occurring. */
    OOM_FIRING
}
