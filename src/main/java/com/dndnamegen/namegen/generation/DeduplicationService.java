package com.dndnamegen.namegen.generation;

import com.dndnamegen.namegen.name.Gender;
import com.dndnamegen.namegen.name.NameRepository;
import com.dndnamegen.namegen.name.Race;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * Pre-filters AI-generated candidates against existing rows for the same
 * race/gender, and against earlier candidates in the same batch. This is an
 * optimization to avoid wasted generation/inserts under normal conditions --
 * the DB unique constraint on (normalized_name, race, gender) is the actual
 * correctness guarantee, since two concurrent generations for the same
 * empty pool can both pass this check before either inserts.
 */
@Service
public class DeduplicationService {

    private final NameRepository nameRepository;

    public DeduplicationService(NameRepository nameRepository) {
        this.nameRepository = nameRepository;
    }

    public List<String> filterDuplicates(Race race, Gender gender, List<String> candidateNames) {
        Set<String> seenNormalized = new HashSet<>(nameRepository.findNormalizedNameByRaceAndGender(race, gender));
        List<String> survivors = new ArrayList<>();
        for (String candidate : candidateNames) {
            if (seenNormalized.add(normalize(candidate))) {
                survivors.add(candidate);
            }
        }
        return survivors;
    }

    /** Matches the normalized_name column: trim, lowercase, Unicode NFC. */
    public static String normalize(String name) {
        return Normalizer.normalize(name.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFC);
    }
}
