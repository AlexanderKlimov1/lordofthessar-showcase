package com.sddnw.server.web;

import com.sddnw.server.dto.PastSelfDto;
import com.sddnw.server.service.PastSelfService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Сравнение с собой прошлым — field-backlog, пункт 115; пусто — сравнивать не с чем. */
@RestController
@RequestMapping("/api/games")
public class PastSelfController {

    private final PastSelfService pastSelf;

    public PastSelfController(PastSelfService pastSelf) {
        this.pastSelf = pastSelf;
    }

    @GetMapping("/{gameId}/past-self")
    public PastSelfDto pastSelf(@PathVariable UUID gameId, @AccessToken String accessToken) {
        return pastSelf.forPlayer(gameId, accessToken);
    }
}
