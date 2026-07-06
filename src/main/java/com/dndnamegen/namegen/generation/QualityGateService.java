package com.dndnamegen.namegen.generation;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Gate an AI-generated candidate must pass before it's eligible for the shared
 * pool: length bounds, an allowed-character whitelist, and a blocklist of
 * real/famous names. This is a pre-insert filter, not a correctness mechanism --
 * the DB unique constraint (see NameInsertDao, landing separately) is what
 * actually prevents duplicates; this service only screens content quality.
 */
@Service
public class QualityGateService {

    /**
     * Letters (Unicode-aware, so accented fantasy names are allowed), optionally
     * separated by a single apostrophe, hyphen, or space -- never at the start/end,
     * never doubled up.
     */
    private static final Pattern ALLOWED_CHARACTERS = Pattern.compile("^\\p{L}+(?:['’\\- ]\\p{L}+)*$");

    private final int minLength;
    private final int maxLength;
    private final Set<String> blocklist;

    public QualityGateService(
            @Value("${app.quality-gate.min-length:2}") int minLength,
            @Value("${app.quality-gate.max-length:30}") int maxLength,
            @Value("${app.quality-gate.blocklist:}") List<String> blocklist) {
        this.minLength = minLength;
        this.maxLength = maxLength;
        this.blocklist = blocklist.stream()
                .map(name -> name.trim().toLowerCase(Locale.ROOT))
                .filter(name -> !name.isEmpty())
                .collect(Collectors.toSet());
    }

    public boolean passesQualityGate(String candidateName) {
        if (candidateName == null) {
            return false;
        }
        String trimmed = candidateName.trim();
        return trimmed.length() >= minLength
                && trimmed.length() <= maxLength
                && ALLOWED_CHARACTERS.matcher(trimmed).matches()
                && !blocklist.contains(trimmed.toLowerCase(Locale.ROOT));
    }
}
