package com.dndnamegen.namegen.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class QualityGateServiceTest {

    private final QualityGateService qualityGateService =
            new QualityGateService(2, 30, List.of("Gandalf", "Harry Potter"));

    @Test
    void passesQualityGate_should_ReturnTrue_When_NameIsWellFormed() {
        assertThat(qualityGateService.passesQualityGate("Aelric")).isTrue();
    }

    @Test
    void passesQualityGate_should_ReturnTrue_When_NameHasApostropheHyphenOrSpace() {
        assertThat(qualityGateService.passesQualityGate("O'Brien")).isTrue();
        assertThat(qualityGateService.passesQualityGate("Aela-Nym")).isTrue();
        assertThat(qualityGateService.passesQualityGate("Al Razan")).isTrue();
    }

    @Test
    void passesQualityGate_should_ReturnFalse_When_NameIsNull() {
        assertThat(qualityGateService.passesQualityGate(null)).isFalse();
    }

    @Test
    void passesQualityGate_should_ReturnFalse_When_NameIsShorterThanMinLength() {
        assertThat(qualityGateService.passesQualityGate("A")).isFalse();
    }

    @Test
    void passesQualityGate_should_ReturnFalse_When_NameIsLongerThanMaxLength() {
        assertThat(qualityGateService.passesQualityGate("A".repeat(31))).isFalse();
    }

    @Test
    void passesQualityGate_should_ReturnFalse_When_NameContainsDigits() {
        assertThat(qualityGateService.passesQualityGate("Aelric1")).isFalse();
    }

    @Test
    void passesQualityGate_should_ReturnFalse_When_NameContainsDisallowedSymbols() {
        assertThat(qualityGateService.passesQualityGate("Aelric!")).isFalse();
        assertThat(qualityGateService.passesQualityGate("Aelric@Home")).isFalse();
    }

    @Test
    void passesQualityGate_should_ReturnFalse_When_NameStartsOrEndsWithSeparator() {
        assertThat(qualityGateService.passesQualityGate("-Aelric")).isFalse();
        assertThat(qualityGateService.passesQualityGate("Aelric-")).isFalse();
    }

    @Test
    void passesQualityGate_should_ReturnFalse_When_NameHasDoubledSeparators() {
        assertThat(qualityGateService.passesQualityGate("Aelric--Nym")).isFalse();
        assertThat(qualityGateService.passesQualityGate("Aelric  Nym")).isFalse();
    }

    @Test
    void passesQualityGate_should_ReturnFalse_When_NameIsOnBlocklist() {
        assertThat(qualityGateService.passesQualityGate("Gandalf")).isFalse();
        assertThat(qualityGateService.passesQualityGate("Harry Potter")).isFalse();
    }

    @Test
    void passesQualityGate_should_ReturnFalse_When_NameMatchesBlocklistCaseInsensitively() {
        assertThat(qualityGateService.passesQualityGate("gandalf")).isFalse();
        assertThat(qualityGateService.passesQualityGate("GANDALF")).isFalse();
    }

    @Test
    void passesQualityGate_should_IgnoreLeadingAndTrailingWhitespace_When_CheckingLengthAndCharset() {
        assertThat(qualityGateService.passesQualityGate("  Aelric  ")).isTrue();
    }
}
