package com.dndnamegen.namegen.generation;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GenerationLogRepository extends JpaRepository<GenerationLog, Long> {
}
