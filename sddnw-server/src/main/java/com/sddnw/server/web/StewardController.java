package com.sddnw.server.web;

import com.sddnw.server.dto.EndTurnRequest;
import com.sddnw.server.dto.StewardDecisionDto;
import com.sddnw.server.service.GameAccess;
import com.sddnw.server.service.StewardService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Что ИИ сделал за вас — field-backlog, пункт 112: решения, которые ИИ принял, пока игрок был
 * в отлучке, и отметка «прочитано».
 */
@RestController
@RequestMapping("/api/games")
public class StewardController {

    private final GameAccess gameAccess;
    private final StewardService steward;

    public StewardController(GameAccess gameAccess, StewardService steward) {
        this.gameAccess = gameAccess;
        this.steward = steward;
    }

    /** Решения опекуна за время отсутствия; пусто — ИИ за игрока ничего не решал. */
    @GetMapping("/{gameId}/steward")
    public List<StewardDecisionDto> decisions(@PathVariable UUID gameId, @AccessToken String accessToken) {
        return steward.decisions(gameAccess.requireToken(gameId, accessToken));
    }

    /** Вернувшийся прочёл решения опекуна — список начнётся заново со следующей отлучки. */
    @PostMapping("/{gameId}/steward/seen")
    public List<StewardDecisionDto> seen(@PathVariable UUID gameId, @Valid @RequestBody EndTurnRequest request) {
        steward.clear(gameAccess.requireToken(gameId, request.accessToken()));
        return List.of();
    }
}
