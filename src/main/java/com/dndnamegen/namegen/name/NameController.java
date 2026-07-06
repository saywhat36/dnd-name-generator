package com.dndnamegen.namegen.name;

import com.dndnamegen.namegen.name.dto.NameResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class NameController {

    private final NameService nameService;

    public NameController(NameService nameService) {
        this.nameService = nameService;
    }

    @GetMapping("/names")
    public List<NameResponse> getNames(@RequestParam Race race, @RequestParam Gender gender) {
        return nameService.getNames(race, gender).stream().map(NameResponse::from).toList();
    }
}
