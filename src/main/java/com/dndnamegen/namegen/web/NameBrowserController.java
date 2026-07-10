package com.dndnamegen.namegen.web;

import com.dndnamegen.namegen.favorite.FavoriteService;
import com.dndnamegen.namegen.generation.PoolReplenishmentService;
import com.dndnamegen.namegen.generation.QualityGateService;
import com.dndnamegen.namegen.identity.Identity;
import com.dndnamegen.namegen.name.Gender;
import com.dndnamegen.namegen.name.Name;
import com.dndnamegen.namegen.name.NameService;
import com.dndnamegen.namegen.name.NameSource;
import com.dndnamegen.namegen.name.NameSourceFilter;
import com.dndnamegen.namegen.name.Race;
import com.dndnamegen.namegen.name.SortOrder;
import com.dndnamegen.namegen.report.NameReportService;
import com.dndnamegen.namegen.user.UserService;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * htmx + Thymeleaf frontend: race/gender/source picker plus favorite/report action
 * buttons, rendered as button groups. See docs/DECISIONS.md.
 */
@Controller
public class NameBrowserController {

    private static final Race DEFAULT_RACE = Race.ELF;
    private static final Gender DEFAULT_GENDER = Gender.FEMININE;
    private static final NameSourceFilter DEFAULT_SOURCE = NameSourceFilter.CURATED;
    private static final SortOrder DEFAULT_SORT = SortOrder.DEFAULT;

    /**
     * Case-insensitive display-name comparator backing the A–Z/Z–A sort toggle (issue #79) --
     * capitalisation is a rendering detail (see index.html's text-transform), so "adrie" and
     * "Adrie" must not sort into different neighbourhoods.
     */
    private static final Comparator<Name> BY_DISPLAY_NAME =
            Comparator.comparing(Name::getDisplayName, String.CASE_INSENSITIVE_ORDER);

    private final NameService nameService;
    private final FavoriteService favoriteService;
    private final NameReportService nameReportService;
    private final PoolReplenishmentService poolReplenishmentService;
    private final QualityGateService qualityGateService;
    private final UserService userService;

    public NameBrowserController(
            NameService nameService,
            FavoriteService favoriteService,
            NameReportService nameReportService,
            PoolReplenishmentService poolReplenishmentService,
            QualityGateService qualityGateService,
            UserService userService) {
        this.nameService = nameService;
        this.favoriteService = favoriteService;
        this.nameReportService = nameReportService;
        this.poolReplenishmentService = poolReplenishmentService;
        this.qualityGateService = qualityGateService;
        this.userService = userService;
    }

    @GetMapping("/")
    public String index(Model model, Identity identity) {
        populateBrowser(model, DEFAULT_RACE, DEFAULT_GENDER, DEFAULT_SOURCE, DEFAULT_SORT, identity);
        return "index";
    }

    @GetMapping("/browse")
    public String browse(
            @RequestParam Race race,
            @RequestParam Gender gender,
            @RequestParam(defaultValue = "CURATED") NameSourceFilter source,
            @RequestParam(defaultValue = "DEFAULT") SortOrder sort,
            Model model,
            Identity identity) {
        populateBrowser(model, race, gender, source, sort, identity);
        return "index :: browser";
    }

    /**
     * Manual "generate more AI names" trigger -- the automatic threshold-triggered
     * replenishment in NameService.getNames only fires while a combo's AI pool is
     * below app.pool-replenishment.replenish-threshold, so once a pool reaches that
     * threshold (its usual resting point after one batch) nothing ever asks for more,
     * even though app.pool-replenishment.cap-per-combo allows further growth. This
     * calls the exact same guarded PoolReplenishmentService.replenish(...) the
     * automatic path uses -- same stampede guard, per-combo cap check, and global
     * daily budget check, so this manual trigger cannot bypass or duplicate any of
     * those cost protections regardless of which provider/model is configured.
     * Fire-and-forget, per the "never block a request on a live LLM call" rule --
     * the re-rendered fragment's "generating" flag drives the client-side polling
     * that reveals the result once the async cycle finishes (see index.html).
     *
     * <p>The "generatingMore" model attribute is forced to true here rather than
     * read from PoolReplenishmentService.isReplenishing(...) via populateBrowser --
     * replenish(...) is @Async, so its in-flight-map update happens on the executor
     * thread, not synchronously before this method continues. Reading isReplenishing
     * immediately afterward races that update and can observe "false" even though a
     * cycle was just requested, which would render this response with the polling
     * indicator missing and no feedback that the click did anything. Since this
     * endpoint only runs when the button that posted to it was visible (not already
     * generating, not at cap -- see index.html), a cycle has genuinely just been
     * requested, so forcing the flag true here is correct, not a guess. Subsequent
     * polls hit GET /browse, which reads the real (by-then-accurate) flag.
     */
    @PostMapping("/browse/generate-more")
    @PreAuthorize("isAuthenticated()")
    public String generateMore(
            @RequestParam Race race,
            @RequestParam Gender gender,
            @RequestParam(defaultValue = "CURATED") NameSourceFilter source,
            @RequestParam(defaultValue = "DEFAULT") SortOrder sort,
            Model model,
            Identity identity) {
        poolReplenishmentService.replenish(race, gender);
        populateBrowser(model, race, gender, source, sort, identity);
        model.addAttribute("generatingMore", true);
        return "index :: browser";
    }

    /**
     * Populates the whole "browser" fragment's model -- button-group options, the current
     * selection (needed so the buttons re-render with the right one highlighted), the
     * matching results, and this session's already-favorited/already-reported name ids (so
     * the per-name action buttons render pre-disabled instead of always starting fresh).
     * Shared by both endpoints since /browse swaps the entire fragment, not just the results
     * list, to keep the clicked button's highlight in sync with the server-side selection it
     * just requested.
     */
    private void populateBrowser(
            Model model,
            Race race,
            Gender gender,
            NameSourceFilter source,
            SortOrder sort,
            Identity identity) {
        List<Name> names = sortNames(nameService.getNames(race, gender, source), sort);
        long aiPoolSize = names.stream().filter(n -> n.getSource() == NameSource.AI_GENERATED).count();

        model.addAttribute("races", Race.values());
        model.addAttribute("genders", Gender.values());
        model.addAttribute("sources", NameSourceFilter.values());
        model.addAttribute("sorts", SortOrder.values());
        model.addAttribute("selectedRace", race);
        model.addAttribute("selectedGender", gender);
        model.addAttribute("selectedSource", source);
        model.addAttribute("selectedSort", sort);
        model.addAttribute("names", names);
        model.addAttribute("submitterUsernames", resolveSubmitterUsernames(names));
        model.addAttribute("favoritedNameIds", favoriteService.getFavoritedNameIds(identity));
        model.addAttribute("reportedNameIds", nameReportService.getReportedNameIds(identity));
        model.addAttribute("aiPoolSize", aiPoolSize);
        model.addAttribute("aiPoolCap", poolReplenishmentService.getPoolCapPerCombo());
        model.addAttribute("generatingMore", poolReplenishmentService.isReplenishing(race, gender));
        // Backs the submit-a-name form's client-side maxlength -- see QualityGateService's
        // getMaxLength() Javadoc for why this reads live config rather than a hardcoded value.
        model.addAttribute("submissionMaxLength", qualityGateService.getMaxLength());
    }

    /**
     * Applies the browser's sort toggle (issue #79) to the already-fetched result list. Done
     * in-memory here rather than as a repository {@code ORDER BY} so a single ordering covers
     * every {@link NameSourceFilter} (including ALL, which merges every source) without each
     * query having to grow a sort clause, and so DEFAULT stays a true no-op that preserves the
     * query's own order. Returns a new list for the sorted cases -- the source list may be
     * immutable (e.g. the repository's or a test's {@code List.of(...)}), so it is never sorted
     * in place.
     */
    private static List<Name> sortNames(List<Name> names, SortOrder sort) {
        return switch (sort) {
            case DEFAULT -> names;
            case A_TO_Z -> names.stream().sorted(BY_DISPLAY_NAME).toList();
            case Z_TO_A -> names.stream().sorted(BY_DISPLAY_NAME.reversed()).toList();
        };
    }

    /**
     * Maps each USER_SUBMITTED name's id to the username of whoever proposed it (issue #81), so
     * the results list can render a "submitted by" byline. Keyed by name id -- not user id --
     * because the template iterates names and needs the lookup per row. Only USER_SUBMITTED rows
     * carry a submitter (see {@link Name#getSubmitterId()}); everything else is skipped, so on the
     * non-user-submitted filters this resolves to an empty map without touching the user store.
     *
     * <p>Batched into a single {@code usernamesByIds} call over the distinct submitter ids rather
     * than one lookup per row. A row whose submitter no longer resolves (e.g. a deleted account)
     * is simply left out of the map, and the template guards on presence, so it degrades to "no
     * byline" rather than failing the render.
     */
    private Map<Long, String> resolveSubmitterUsernames(List<Name> names) {
        Set<Long> submitterIds = names.stream()
                .filter(n -> n.getSource() == NameSource.USER_SUBMITTED && n.getSubmitterId() != null)
                .map(Name::getSubmitterId)
                .collect(Collectors.toSet());
        if (submitterIds.isEmpty()) {
            return Map.of();
        }

        Map<Long, String> usernamesByUserId = userService.usernamesByIds(submitterIds);
        Map<Long, String> usernamesByNameId = new LinkedHashMap<>();
        for (Name name : names) {
            if (name.getSource() == NameSource.USER_SUBMITTED && name.getSubmitterId() != null) {
                String username = usernamesByUserId.get(name.getSubmitterId());
                if (username != null) {
                    usernamesByNameId.put(name.getId(), username);
                }
            }
        }
        return usernamesByNameId;
    }
}
