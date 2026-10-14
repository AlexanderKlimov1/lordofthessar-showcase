package com.sddnw.server.web;

import com.sddnw.server.dto.GameNoteDto;
import com.sddnw.server.repository.PlayerRepository;
import com.sddnw.server.service.GameAccess;
import com.sddnw.server.web.error.BadRequestException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Записка к партии — field-backlog, пункт 110: внешняя память замысла. Только своему игроку —
 * это его мысли, а не состояние партии; соседям её не видно нигде.
 */
@RestController
@RequestMapping("/api/games")
public class GameNoteController {

    /** Заметка — строка, а не дневник: их перечитывают с правой панели каждый ход. */
    public static final int MAX_LENGTH = 200;

    /**
     * Заметок не больше трёх (08.10.2026, решение хозяина проекта): хранятся одной колонкой, по
     * строке на заметку, — у игрока одна запись на партию, и своя таблица ради трёх строк не нужна.
     */
    public static final int MAX_NOTES = 3;

    private final GameAccess gameAccess;
    private final PlayerRepository players;

    public GameNoteController(GameAccess gameAccess, PlayerRepository players) {
        this.gameAccess = gameAccess;
        this.players = players;
    }

    @GetMapping("/{gameId}/note")
    public GameNoteDto note(@PathVariable UUID gameId, @AccessToken String accessToken) {
        UUID me = gameAccess.requireToken(gameId, accessToken);
        return new GameNoteDto(players.findNoteById(me).orElse(null));
    }

    /** Пустая строка стирает записку. */
    @PutMapping("/{gameId}/note")
    @Transactional
    public GameNoteDto save(@PathVariable UUID gameId, @AccessToken String accessToken,
                            @RequestBody GameNoteDto request) {
        UUID me = gameAccess.requireToken(gameId, accessToken);
        List<String> lines = (request.note() == null ? "" : request.note()).lines()
                .map(String::strip).filter(line -> !line.isEmpty()).toList();
        if (lines.size() > MAX_NOTES) {
            throw new BadRequestException("note.tooMany", String.valueOf(MAX_NOTES));
        }
        if (lines.stream().anyMatch(line -> line.length() > MAX_LENGTH)) {
            throw new BadRequestException("note.tooLong", String.valueOf(MAX_LENGTH));
        }
        String note = String.join("\n", lines);
        players.updateNote(me, note.isEmpty() ? null : note);
        return new GameNoteDto(note.isEmpty() ? null : note);
    }
}
