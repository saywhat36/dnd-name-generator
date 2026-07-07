package com.dndnamegen.namegen.name;

import com.dndnamegen.namegen.name.dto.NameResponse;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class NameController {

    private final NameService nameService;

    public NameController(NameService nameService) {
        this.nameService = nameService;
    }

    @GetMapping("/names")
    public List<NameResponse> getNames(
            @RequestParam Race race,
            @RequestParam Gender gender,
            @RequestParam(defaultValue = "CURATED") NameSourceFilter source) {
        return nameService.getNames(race, gender, source).stream().map(NameResponse::from).toList();
    }

    @PostMapping("/names/{id}/flag")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void flagName(@PathVariable Long id) {
        if (!nameService.flagName(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No name with id " + id);
        }
    }
}
