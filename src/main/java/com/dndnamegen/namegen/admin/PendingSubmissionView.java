package com.dndnamegen.namegen.admin;

import com.dndnamegen.namegen.name.Gender;
import com.dndnamegen.namegen.name.Race;
import java.time.Instant;

/** One row of the admin submissions queue: a pending candidate name and who proposed it. */
public record PendingSubmissionView(
        Long submissionId, String displayName, Race race, Gender gender, String submitterUsername, Instant createdAt) {}
