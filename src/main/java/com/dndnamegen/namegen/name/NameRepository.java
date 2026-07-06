package com.dndnamegen.namegen.name;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NameRepository extends JpaRepository<Name, Long> {

    List<Name> findByRaceAndGenderAndStatusAndSource(
            Race race, Gender gender, NameStatus status, NameSource source);
}
