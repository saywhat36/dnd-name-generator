package com.dndnamegen.namegen.name.dto;

import com.dndnamegen.namegen.name.Name;

public record NameResponse(Long id, String displayName) {

    public static NameResponse from(Name name) {
        return new NameResponse(name.getId(), name.getDisplayName());
    }
}
