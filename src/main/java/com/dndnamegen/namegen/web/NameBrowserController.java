package com.dndnamegen.namegen.web;

import com.dndnamegen.namegen.name.Gender;
import com.dndnamegen.namegen.name.NameService;
import com.dndnamegen.namegen.name.Race;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Browse-only slice of the Week 6 htmx + Thymeleaf frontend: race/gender picker over
 * curated names. No source toggle, favorite/report actions, or pool-low notice --
 * those depend on Week 3-5 features that don't exist yet. See docs/DECISIONS.md.
 */
@Controller
public class NameBrowserController {

    private static final Race DEFAULT_RACE = Race.ELF;
    private static final Gender DEFAULT_GENDER = Gender.FEMININE;

    private final NameService nameService;

    public NameBrowserController(NameService nameService) {
        this.nameService = nameService;
    }

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("races", Race.values());
        model.addAttribute("genders", Gender.values());
        model.addAttribute("selectedRace", DEFAULT_RACE);
        model.addAttribute("selectedGender", DEFAULT_GENDER);
        model.addAttribute("names", nameService.getNames(DEFAULT_RACE, DEFAULT_GENDER));
        return "index";
    }

    @GetMapping("/browse")
    public String browse(@RequestParam Race race, @RequestParam Gender gender, Model model) {
        model.addAttribute("names", nameService.getNames(race, gender));
        return "index :: list";
    }
}
