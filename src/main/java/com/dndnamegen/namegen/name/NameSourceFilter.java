package com.dndnamegen.namegen.name;

/**
 * Request-facing source toggle for {@code GET /names} -- distinct from {@link NameSource},
 * which is the persisted column on {@code names} and has no BOTH value of its own.
 */
public enum NameSourceFilter {
    CURATED,
    AI_GENERATED,
    BOTH
}
