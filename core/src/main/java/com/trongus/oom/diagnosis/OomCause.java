package com.trongus.oom.diagnosis;

/**
 * Immutable value object produced by {@link OomCauseAnalyser} that pairs a
 * {@link OomCauseCategory} with a localised, plain-language explanation
 * string suitable for display in alert messages, log entries, and dashboards.
 *
 * <p>Instances are obtained exclusively through
 * {@link OomCauseAnalyser#analyse(double, double, double)}.
 *
 * <h2>Immutability</h2>
 * Both fields are {@code final}; the class has no setters and no mutable
 * state.  Instances are safe to share across threads without synchronisation.
 *
 * @author <a href="mailto:kristen.gillard@gmail.com">Kristen Gillard</a>
 * @version 1.7.0
 * @since 1.3.0
 * @see OomCauseCategory
 * @see OomCauseAnalyser
 */
public final class OomCause {

    /** The detected root-cause category. Never {@code null}. */
    private final OomCauseCategory category;

    /**
     * Localised, human-readable explanation of the cause.
     * The string is already fully formatted; callers may embed it directly
     * in alert text without further processing.  Never {@code null}.
     */
    private final String explanation;

    /**
     * Constructs an {@code OomCause}.
     *
     * @param category    the root-cause category; must not be {@code null}
     * @param explanation the localised explanation string; must not be {@code null}
     * @throws NullPointerException if either argument is {@code null}
     */
    OomCause(OomCauseCategory category, String explanation) {
        if (category == null)    throw new NullPointerException("category");
        if (explanation == null) throw new NullPointerException("explanation");
        this.category    = category;
        this.explanation = explanation;
    }

    /**
     * Returns the detected root-cause category.
     *
     * @return the category; never {@code null}
     */
    public OomCauseCategory getCategory() {
        return category;
    }

    /**
     * Returns a localised, plain-language explanation of the cause.
     *
     * <p>The explanation is formatted in the locale that was active on the
     * {@link OomCauseAnalyser} that produced this instance.
     *
     * @return the explanation string; never {@code null}
     */
    public String getExplanation() {
        return explanation;
    }

    /**
     * Returns a debug-friendly representation including the category name
     * and explanation.
     *
     * @return string in the form {@code OomCause{category=X, explanation="Y"}}
     */
    @Override
    public String toString() {
        return "OomCause{category=" + category + ", explanation=\"" + explanation + "\"}";
    }
}
