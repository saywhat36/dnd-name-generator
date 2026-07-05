package com.dndnamegen.namegen.name;

import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Week 1: curated names only. AI pool + source toggle (CURATED / AI_GENERATED / BOTH)
 * lands in Week 3 once PoolReplenishmentService exists.
 */
@Service
public class NameService {

    private final NameRepository nameRepository;

    public NameService(NameRepository nameRepository) {
        this.nameRepository = nameRepository;
    }

    public List<Name> getNames(Race race, Gender gender) {
        return nameRepository.findByRaceAndGenderAndStatusAndSource(
                race, gender, NameStatus.ACTIVE, NameSource.CURATED);
    }
}
