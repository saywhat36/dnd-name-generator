package com.dndnamegen.namegen.web;

import com.dndnamegen.namegen.favorite.FavoriteService;
import com.dndnamegen.namegen.name.Gender;
import com.dndnamegen.namegen.name.NameService;
import com.dndnamegen.namegen.name.NameSourceFilter;
import com.dndnamegen.namegen.name.Race;
import com.dndnamegen.namegen.report.NameReportService;
import com.dndnamegen.namegen.session.SessionIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
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

    public NameBrowserController(
            NameService nameService, FavoriteService favoriteService, NameReportService nameReportService) {
        this.nameService = nameService;
        this.favoriteService = favoriteService;
        this.nameReportService = nameReportService;
    }

    @GetMapping("/")
    public String index(Model model, HttpServletRequest request) {
        populateBrowser(model, DEFAULT_RACE, DEFAULT_GENDER, DEFAULT_SOURCE, request);
        return "index";
    }

    @GetMapping("/browse")
    public String browse(
            @RequestParam Race race,
            @RequestParam Gender gender,
            @RequestParam(defaultValue = "CURATED") NameSourceFilter source,
            Model model,
            HttpServletRequest request) {
        populateBrowser(model, race, gender, source, request);
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
            Model model, Race race, Gender gender, NameSourceFilter source, HttpServletRequest request) {
        String sessionId = (String) request.getAttribute(SessionIdFilter.REQUEST_ATTRIBUTE);
        model.addAttribute("races", Race.values());
        model.addAttribute("genders", Gender.values());
        model.addAttribute("sources", NameSourceFilter.values());
        model.addAttribute("selectedRace", race);
        model.addAttribute("selectedGender", gender);
        model.addAttribute("selectedSource", source);
        model.addAttribute("names", nameService.getNames(race, gender, source));
        model.addAttribute("favoritedNameIds", favoriteService.getFavoritedNameIds(sessionId));
        model.addAttribute("reportedNameIds", nameReportService.getReportedNameIds(sessionId));
    }
}
