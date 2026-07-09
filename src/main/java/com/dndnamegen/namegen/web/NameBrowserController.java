package com.dndnamegen.namegen.web;

import com.dndnamegen.namegen.favorite.FavoriteService;
import com.dndnamegen.namegen.generation.PoolReplenishmentService;
import com.dndnamegen.namegen.identity.Identity;
import com.dndnamegen.namegen.name.Gender;
import com.dndnamegen.namegen.name.Name;
import com.dndnamegen.namegen.name.NameService;
import com.dndnamegen.namegen.name.NameSource;
import com.dndnamegen.namegen.name.NameSourceFilter;
import com.dndnamegen.namegen.name.Race;
import com.dndnamegen.namegen.report.NameReportService;
import java.util.List;
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

    private final NameService nameService;
    private final FavoriteService favoriteService;
    private final NameReportService nameReportService;
    private final PoolReplenishmentService poolReplenishmentService;

    public NameBrowserController(
            NameService nameService,
            FavoriteService favoriteService,
            NameReportService nameReportService,
            PoolReplenishmentService poolReplenishmentService) {
        this.nameService = nameService;
        this.favoriteService = favoriteService;
        this.nameReportService = nameReportService;
        this.poolReplenishmentService = poolReplenishmentService;
    }

    @GetMapping("/")
    public String index(Model model, Identity identity) {
        populateBrowser(model, DEFAULT_RACE, DEFAULT_GENDER, DEFAULT_SOURCE, identity);
        return "index";
    }

    @GetMapping("/browse")
    public String browse(
            @RequestParam Race race,
            @RequestParam Gender gender,
            @RequestParam(defaultValue = "CURATED") NameSourceFilter source,
            Model model,
            Identity identity) {
        populateBrowser(model, race, gender, source, identity);
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
    public String generateMore(
            @RequestParam Race race,
            @RequestParam Gender gender,
            @RequestParam(defaultValue = "CURATED") NameSourceFilter source,
            Model model,
            Identity identity) {
        poolReplenishmentService.replenish(race, gender);
        populateBrowser(model, race, gender, source, identity);
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
            Model model, Race race, Gender gender, NameSourceFilter source, Identity identity) {
        List<Name> names = nameService.getNames(race, gender, source);
        long aiPoolSize = names.stream().filter(n -> n.getSource() == NameSource.AI_GENERATED).count();

        model.addAttribute("races", Race.values());
        model.addAttribute("genders", Gender.values());
        model.addAttribute("sources", NameSourceFilter.values());
        model.addAttribute("selectedRace", race);
        model.addAttribute("selectedGender", gender);
        model.addAttribute("selectedSource", source);
        model.addAttribute("names", names);
        model.addAttribute("favoritedNameIds", favoriteService.getFavoritedNameIds(identity));
        model.addAttribute("reportedNameIds", nameReportService.getReportedNameIds(identity));
        model.addAttribute("aiPoolSize", aiPoolSize);
        model.addAttribute("aiPoolCap", poolReplenishmentService.getPoolCapPerCombo());
        model.addAttribute("generatingMore", poolReplenishmentService.isReplenishing(race, gender));
    }
}
