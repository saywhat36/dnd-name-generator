package com.dndnamegen.namegen.name;

/**
 * Request-facing sort toggle for the name browser (issue #79). Purely a presentation
 * concern -- it reorders the already-fetched result list in NameBrowserController and never
 * reaches the repository, so it applies uniformly across every {@link NameSourceFilter}
 * without a per-query {@code ORDER BY}.
 *
 * <p>Like {@link NameSourceFilter}, the {@code label} is the human-facing text the picker
 * button group renders (see index.html reads {@code o.label}), so every value has an
 * unambiguous label and adding a value here can't silently render as another's text.
 *
 * <p>DEFAULT preserves whatever order the query returned (unchanged pre-#79 behaviour), so it
 * is the default the controller falls back to and the resting state of the toggle.
 */
public enum SortOrder {
    DEFAULT("Default"),
    A_TO_Z("A–Z"),
    Z_TO_A("Z–A");

    private final String label;

    SortOrder(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
