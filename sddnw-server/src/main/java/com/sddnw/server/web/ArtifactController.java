package com.sddnw.server.web;

import com.sddnw.server.dto.AcquiredTechnologyDto;
import com.sddnw.server.dto.ArtifactChoiceDto;
import com.sddnw.server.dto.ArtifactPickRequest;
import com.sddnw.server.service.PlanetFindReward;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Наследие ушедших на выбор — backlog-promo, пункт 34. */
@RestController
@RequestMapping("/api/games/{gameId}/artifacts")
public class ArtifactController {

    private final PlanetFindReward findReward;

    public ArtifactController(PlanetFindReward findReward) {
        this.findReward = findReward;
    }

    @GetMapping
    public List<ArtifactChoiceDto> pending(@PathVariable UUID gameId, @AccessToken String accessToken) {
        return findReward.pending(gameId, accessToken);
    }

    @PostMapping("/{choiceId}")
    public List<AcquiredTechnologyDto> pick(@PathVariable UUID gameId, @PathVariable UUID choiceId,
                                            @Valid @RequestBody ArtifactPickRequest request) {
        return findReward.pick(gameId, choiceId, request.accessToken(), request.codes());
    }
}
