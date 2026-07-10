package com.dndnamegen.namegen.submission.dto;

import com.dndnamegen.namegen.name.Gender;
import com.dndnamegen.namegen.name.Race;
import com.dndnamegen.namegen.submission.NameSubmission;
import com.dndnamegen.namegen.submission.SubmissionStatus;
import java.time.Instant;

/** One row of "my submissions" -- what the caller proposed and where it stands. */
public record SubmissionResponse(
        Long id, String displayName, Race race, Gender gender, SubmissionStatus status, Instant createdAt) {

    public static SubmissionResponse from(NameSubmission submission) {
        return new SubmissionResponse(
                submission.getId(),
                submission.getDisplayName(),
                submission.getRace(),
                submission.getGender(),
                submission.getStatus(),
                submission.getCreatedAt());
    }
}
