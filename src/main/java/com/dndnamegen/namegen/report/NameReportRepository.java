package com.dndnamegen.namegen.report;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NameReportRepository extends JpaRepository<NameReport, Long> {

    Optional<NameReport> findBySessionIdAndNameId(String sessionId, Long nameId);
}
