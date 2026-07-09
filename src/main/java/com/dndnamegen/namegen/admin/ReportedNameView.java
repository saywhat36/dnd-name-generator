package com.dndnamegen.namegen.admin;

import com.dndnamegen.namegen.name.NameStatus;
import java.util.List;

/** One row of the admin reports table: a reported name, its status, and a taste of why. */
public record ReportedNameView(
        Long nameId, String displayName, NameStatus status, long reportCount, List<String> reasonSamples) {}
