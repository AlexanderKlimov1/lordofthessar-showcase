package com.sddnw.server.web;

import com.sddnw.server.dto.SpectatableGameDto;
import com.sddnw.server.dto.SpectatorFrameDto;
import com.sddnw.server.service.SpectatorService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Режим зрителя — field-backlog, пункт 59. Адрес вне партии: зритель за столом не сидит, и
 * пропуска игрока у него нет — спрашивается учётная запись.
 */
@RestController
@RequestMapping("/api/spectate")
public class SpectatorController {

    private final SpectatorService spectators;

    public SpectatorController(SpectatorService spectators) {
        this.spectators = spectators;
    }

    /** Партии, которые можно смотреть. */
    @GetMapping
    public List<SpectatableGameDto> list(@AccountToken String accountToken) {
        return spectators.list(accountToken);
    }

    /** Партия глазами зрителя — с отставанием на {@code SpectatorService.DELAY_TURNS} ходов. */
    @GetMapping("/{gameId}")
    public SpectatorFrameDto frame(@PathVariable UUID gameId, @AccountToken String accountToken) {
        return spectators.frame(gameId, accountToken);
    }
}
