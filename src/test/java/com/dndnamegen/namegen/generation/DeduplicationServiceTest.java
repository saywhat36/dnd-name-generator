package com.dndnamegen.namegen.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.dndnamegen.namegen.name.Gender;
import com.dndnamegen.namegen.name.NameRepository;
import com.dndnamegen.namegen.name.Race;
import java.text.Normalizer;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeduplicationServiceTest {

    private final NameRepository nameRepository = mock(NameRepository.class);
    private final DeduplicationService deduplicationService = new DeduplicationService(nameRepository);

    @Test
    void filterDuplicates_should_KeepAllCandidates_When_NoneCollideWithExistingOrEachOther() {
        when(nameRepository.findNormalizedNameByRaceAndGender(Race.ELF, Gender.FEMININE))
                .thenReturn(List.of("aelric"));

        List<String> result =
                deduplicationService.filterDuplicates(Race.ELF, Gender.FEMININE, List.of("Sylvaine", "Nymrienne"));

        assertThat(result).containsExactly("Sylvaine", "Nymrienne");
    }

    @Test
    void filterDuplicates_should_ExcludeCandidate_When_ItCollidesWithAnExistingNormalizedName() {
        when(nameRepository.findNormalizedNameByRaceAndGender(Race.ELF, Gender.FEMININE))
                .thenReturn(List.of("aelric"));

        List<String> result =
                deduplicationService.filterDuplicates(Race.ELF, Gender.FEMININE, List.of("Aelric", "Sylvaine"));

        assertThat(result).containsExactly("Sylvaine");
    }

    @Test
    void filterDuplicates_should_ExcludeCollision_When_CaseOrWhitespaceDiffersFromExistingName() {
        when(nameRepository.findNormalizedNameByRaceAndGender(Race.ELF, Gender.FEMININE))
                .thenReturn(List.of("aelric"));

        List<String> result =
                deduplicationService.filterDuplicates(Race.ELF, Gender.FEMININE, List.of("  AELRIC  ", "Sylvaine"));

        assertThat(result).containsExactly("Sylvaine");
    }

    @Test
    void filterDuplicates_should_KeepOnlyFirstOccurrence_When_TwoCandidatesNormalizeTheSameWithinOneBatch() {
        when(nameRepository.findNormalizedNameByRaceAndGender(Race.ELF, Gender.FEMININE))
                .thenReturn(List.of());

        List<String> result = deduplicationService.filterDuplicates(
                Race.ELF, Gender.FEMININE, List.of("Sylvaine", "sylvaine", "Nymrienne"));

        assertThat(result).containsExactly("Sylvaine", "Nymrienne");
    }

    @Test
    void filterDuplicates_should_ExcludeCollision_When_ExistingNameDiffersOnlyByUnicodeNormalizationForm() {
        String precomposed = "Elyra".replaceFirst("E", "É"); // "Élyra", one codepoint per accented letter (NFC)
        String decomposed = Normalizer.normalize(precomposed, Normalizer.Form.NFD); // base letter + combining accent
        assertThat(decomposed).isNotEqualTo(precomposed); // sanity-check the two forms actually differ as strings

        when(nameRepository.findNormalizedNameByRaceAndGender(Race.ELF, Gender.FEMININE))
                .thenReturn(List.of(DeduplicationService.normalize(precomposed)));

        List<String> result =
                deduplicationService.filterDuplicates(Race.ELF, Gender.FEMININE, List.of(decomposed, "Sylvaine"));

        assertThat(result).containsExactly("Sylvaine");
    }
}
