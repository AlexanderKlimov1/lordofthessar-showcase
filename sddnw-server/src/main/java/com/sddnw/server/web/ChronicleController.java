package com.sddnw.server.web;

import com.sddnw.server.dto.GameEndingDto;
import com.sddnw.server.service.GameAccess;
import com.sddnw.server.service.Messages;
import com.sddnw.server.service.MilestoneService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Хроника партии — field-backlog, пункт 138: всё заметное, что случилось с игроком за партию,
 * по ходам и на языке читателя. Хранится ключом с подстановками (как отчёт хода) и переводится
 * здесь, при чтении. Своя хроника — только своему игроку: в ней чужие имена и войны, известные
 * ему одному.
 */
@RestController
@RequestMapping("/api/games")
public class ChronicleController {

    private final GameAccess gameAccess;
    private final MilestoneService milestones;
    private final Messages messages;

    public ChronicleController(GameAccess gameAccess, MilestoneService milestones, Messages messages) {
        this.gameAccess = gameAccess;
        this.milestones = milestones;
        this.messages = messages;
    }

    @GetMapping("/{gameId}/chronicle")
    public List<GameEndingDto.Moment> chronicle(@PathVariable UUID gameId, @AccessToken String accessToken) {
        UUID me = gameAccess.requireToken(gameId, accessToken);
        return milestones.chronicle(me).stream()
                .map(row -> new GameEndingDto.Moment(row.getTurn(), row.getKind(),
                        messages.event(row.getMessageKey(), row.getArgs() == null ? List.of() : row.getArgs())))
                .toList();
    }
}
