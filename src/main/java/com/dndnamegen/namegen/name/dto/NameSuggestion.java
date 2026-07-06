package com.dndnamegen.namegen.name.dto;

/**
 * A single candidate name as returned by the LLM's structured output, before
 * quality-gate and deduplication filtering are applied.
 */
public record NameSuggestion(String name) {
}
