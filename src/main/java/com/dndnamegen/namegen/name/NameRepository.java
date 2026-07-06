package com.dndnamegen.namegen.name;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NameRepository extends JpaRepository<Name, Long> {

    List<Name> findByRaceAndGenderAndStatusAndSource(
            Race race, Gender gender, NameStatus status, NameSource source);

    /**
     * Every existing normalized name for this race/gender, regardless of status or
     * source -- the (normalized_name, race, gender) unique constraint applies across
     * all rows, not just ACTIVE/CURATED ones, so dedup pre-filtering must too.
     */
    List<String> findNormalizedNameByRaceAndGender(Race race, Gender gender);
}
