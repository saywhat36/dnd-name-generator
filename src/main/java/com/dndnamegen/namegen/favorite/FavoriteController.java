package com.dndnamegen.namegen.favorite;

import com.dndnamegen.namegen.identity.Identity;
import com.dndnamegen.namegen.name.NameRepository;
import com.dndnamegen.namegen.name.dto.NameResponse;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class FavoriteController {

    private final FavoriteService favoriteService;
    private final NameRepository nameRepository;

    public FavoriteController(FavoriteService favoriteService, NameRepository nameRepository) {
        this.favoriteService = favoriteService;
        this.nameRepository = nameRepository;
    }

    @PostMapping("/favorites")
    @ResponseStatus(HttpStatus.CREATED)
    public void addFavorite(@RequestParam Long nameId, Identity identity) {
        requireNameExists(nameId);
        favoriteService.addFavorite(identity, nameId);
    }

    @DeleteMapping("/favorites/{nameId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeFavorite(@PathVariable Long nameId, Identity identity) {
        favoriteService.removeFavorite(identity, nameId);
    }

    @GetMapping("/favorites")
    public List<NameResponse> listFavorites(Identity identity) {
        return favoriteService.listFavorites(identity).stream()
                .map(NameResponse::from)
                .toList();
    }

    private void requireNameExists(Long nameId) {
        if (!nameRepository.existsById(nameId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No name with id " + nameId);
        }
    }
}
