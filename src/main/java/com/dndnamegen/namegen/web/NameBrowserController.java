package com.dndnamegen.namegen.web;

import com.dndnamegen.namegen.name.Gender;
import com.dndnamegen.namegen.name.NameService;
import com.dndnamegen.namegen.name.NameSourceFilter;
import com.dndnamegen.namegen.name.Race;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * htmx + Thymeleaf frontend: race/gender/source picker, rendered as button groups rather
 * than dropdowns so a click both selects and immediately re-queries. Favorite/report
 * actions and the pool-low notice are still out of scope -- the former depend on Week 5's
 * not-yet-built favorite/report backend, the latter on Week 4's provider-switching slice.
 * See docs/DECISIONS.md.
 */
@Controller
public class NameBrowserController {

    private static final Race DEFAULT_RACE = Race.ELF;
    private static final Gender DEFAULT_GENDER = Gender.FEMININE;
    private static final NameSourceFilter DEFAULT_SOURCE = NameSourceFilter.CURATED;

    private final NameService nameService;

    public NameBrowserController(NameService nameService) {
        this.nameService = nameService;
    }

    @GetMapping("/")
    public String index(Model model) {
        populateBrowser(model, DEFAULT_RACE, DEFAULT_GENDER, DEFAULT_SOURCE);
        return "index";
    }

    @GetMapping("/browse")
    public String browse(
            @RequestParam Race race,
            @RequestParam Gender gender,
            @RequestParam(defaultValue = "CURATED") NameSourceFilter source,
            Model model) {
        populateBrowser(model, race, gender, source);
        return "index :: browser";
    }

    /**
     * Populates the whole "browser" fragment's model -- button-group options plus the
     * current selection (needed so the buttons re-render with the right one highlighted)
     * and the matching results. Shared by both endpoints since /browse swaps the entire
     * fragment, not just the results list, to keep the clicked button's highlight in sync
     * with the server-side selection it just requested.
     */
    private void populateBrowser(Model model, Race race, Gender gender, NameSourceFilter source) {
        model.addAttribute("races", Race.values());
        model.addAttribute("genders", Gender.values());
        model.addAttribute("sources", NameSourceFilter.values());
        model.addAttribute("selectedRace", race);
        model.addAttribute("selectedGender", gender);
        model.addAttribute("selectedSource", source);
        model.addAttribute("names", nameService.getNames(race, gender, source));
    }
}
