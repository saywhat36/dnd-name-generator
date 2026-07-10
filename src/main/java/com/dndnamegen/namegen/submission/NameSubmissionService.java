package com.dndnamegen.namegen.submission;

import com.dndnamegen.namegen.generation.DeduplicationService;
import com.dndnamegen.namegen.generation.QualityGateService;
import com.dndnamegen.namegen.identity.Identity;
import com.dndnamegen.namegen.name.Gender;
import com.dndnamegen.namegen.name.NameRepository;
import com.dndnamegen.namegen.name.Race;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Enqueues a user-submitted candidate name into the pending review queue, keyed on
 * {@link Identity#ownerId()}. A submission is not a live name -- approval (a separate admin
 * action, not yet built) is the moment it lands in {@code names}. Mirrors {@code NameReportService}'s
 * shape: a raw user-facing write, owner-keyed, with the DB unique constraint as the race-safe
 * backstop behind an application-level pre-check.
 */
@Service
public class NameSubmissionService {

    private final NameSubmissionRepository nameSubmissionRepository;
    private final NameRepository nameRepository;
    private final QualityGateService qualityGateService;

    public NameSubmissionService(
            NameSubmissionRepository nameSubmissionRepository,
            NameRepository nameRepository,
            QualityGateService qualityGateService) {
        this.nameSubmissionRepository = nameSubmissionRepository;
        this.nameRepository = nameRepository;
        this.qualityGateService = qualityGateService;
    }

    /**
     * The anti-abuse gate first, then two friendly-409 duplicate checks, then the insert.
     * {@code displayName} is expected already trimmed/length-bounded by the controller.
     *
     * <ol>
     *   <li>Quality gate: the exact charset/length/blocklist screen AI names pass -- this is what
     *       stops a logged-in user injecting slurs or junk into the shared pool, so it is not
     *       optional.
     *   <li>A live name already exists for this (name, race, gender) in any status -> 409.
     *   <li>A pending submission already exists for this (name, race, gender) -> 409.
     *   <li>Otherwise save. The uq_submissions_pending constraint is the real guard for a
     *       concurrent duplicate that races past check (3): its
     *       {@link DataIntegrityViolationException} is caught and mapped to the same 409, mirroring
     *       {@code NameReportService.saveNew}'s catch-and-remap.
     * </ol>
     */
    public NameSubmission submit(Identity identity, Race race, Gender gender, String displayName) {
        if (!qualityGateService.passesQualityGate(displayName)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "name failed the content quality gate");
        }

        String normalizedName = DeduplicationService.normalize(displayName);

        if (nameRepository.existsByNormalizedNameAndRaceAndGender(normalizedName, race, gender)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "a name already exists for this race and gender");
        }
        if (nameSubmissionRepository.existsByNormalizedNameAndRaceAndGenderAndStatus(
                normalizedName, race, gender, SubmissionStatus.PENDING)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "a submission for this name is already pending review");
        }

        try {
            return nameSubmissionRepository.save(
                    new NameSubmission(identity.ownerId(), displayName, race, gender));
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "a submission for this name is already pending review", e);
        }
    }

    /**
     * "My submissions" (read-only, owner-keyed): every submission this identity has made,
     * regardless of status, most-recent-first. Deliberately no anonymous fallback -- unlike
     * {@code getReportedNameIds}, which the public browse page calls for every visitor,
     * {@code /submissions/mine} is itself an authenticated-only route (see {@code
     * NameSubmissionController}/{@code WebSecurityConfig}), so {@code identity.ownerId()} is
     * always non-null here.
     */
    public List<NameSubmission> listMySubmissions(Identity identity) {
        return nameSubmissionRepository.findBySubmitterIdOrderByCreatedAtDescIdDesc(identity.ownerId());
    }
}
