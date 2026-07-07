package com.dndnamegen.namegen.report;

import com.dndnamegen.namegen.name.NameRepository;
import com.dndnamegen.namegen.session.SessionIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class NameReportController {

    private final NameReportService nameReportService;
    private final NameRepository nameRepository;

    public NameReportController(NameReportService nameReportService, NameRepository nameRepository) {
        this.nameReportService = nameReportService;
        this.nameRepository = nameRepository;
    }

    @PostMapping("/reports")
    @ResponseStatus(HttpStatus.CREATED)
    public void reportName(
            @RequestParam Long nameId, @RequestParam(required = false) String reason, HttpServletRequest request) {
        requireNameExists(nameId);
        nameReportService.reportName(sessionId(request), nameId, reason);
    }

    private void requireNameExists(Long nameId) {
        if (!nameRepository.existsById(nameId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No name with id " + nameId);
        }
    }

    private String sessionId(HttpServletRequest request) {
        return (String) request.getAttribute(SessionIdFilter.REQUEST_ATTRIBUTE);
    }
}
