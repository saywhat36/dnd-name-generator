package com.dndnamegen.namegen.name;

/**
 * Request-facing source toggle for {@code GET /names} -- distinct from {@link NameSource},
 * which is the persisted column on {@code names} and has no ALL value of its own.
 *
 * <p>The {@code label} is the human-facing text the source picker button group renders
 * (see index.html) -- the template reads {@code s.label} rather than deriving it inline, so
 * every value has an unambiguous label and adding a value here can't silently collide with
 * another's text (before issue #81 the inline expression rendered both CURATED and the
 * combined filter as "Handbook").
 *
 * <p>USER_SUBMITTED is its own filter (issue #81): approved user-submitted names surface
 * under this toggle as well as under ALL. ALL means every served source -- curated, AI, and
 * approved user submissions -- so its label matches its behaviour.
 *
 * <p>Declaration order here is also the button order in the picker (the template iterates
 * {@code values()}), so ALL sits last, after its constituent filters.
 */
public enum NameSourceFilter {
    CURATED("Handbook"),
    AI_GENERATED("AI"),
    USER_SUBMITTED("User submitted"),
    ALL("All");

    private final String label;

    NameSourceFilter(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
