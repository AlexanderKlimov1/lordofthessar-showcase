package com.sddnw.server.web;

import com.sddnw.server.dto.VictoryProgressDto;
import com.sddnw.server.service.VictoryProgressService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Прогресс к каждому пути к победе и ближайшая веха — field-backlog, пункты 51 и 11. */
@RestController
@RequestMapping("/api/games")
public class VictoryProgressController {

    private final VictoryProgressService progress;

    public VictoryProgressController(VictoryProgressService progress) {
        this.progress = progress;
    }

    @GetMapping("/{gameId}/victory-progress")
    public VictoryProgressDto progress(@PathVariable UUID gameId, @AccessToken String accessToken) {
        return progress.progress(gameId, accessToken);
    }
}
