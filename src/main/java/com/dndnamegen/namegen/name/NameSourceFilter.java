package com.dndnamegen.namegen.name;

/**
 * Request-facing source toggle for {@code GET /names} -- distinct from {@link NameSource},
 * which is the persisted column on {@code names} and has no BOTH value of its own.
 *
 * <p>The {@code label} is the human-facing text the source picker button group renders
 * (see index.html) -- the template reads {@code s.label} rather than deriving it inline, so
 * every value has an unambiguous label and adding a value here can't silently collide with
 * another's text (before issue #81 the inline expression rendered both CURATED and BOTH as
 * "Handbook").
 *
 * <p>USER_SUBMITTED is its own filter (issue #81): approved user-submitted names surface only
 * under this toggle, no longer folded into CURATED. BOTH stays "the two generation sources"
 * (curated + AI), so user-submitted names are reachable only via their dedicated filter.
 */
public enum NameSourceFilter {
    CURATED("Handbook"),
    AI_GENERATED("AI"),
    BOTH("Both"),
    USER_SUBMITTED("User submitted");

    private final String label;

    NameSourceFilter(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
