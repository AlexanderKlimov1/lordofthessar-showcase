package com.sddnw.server.web;

import com.sddnw.server.dto.GameSignalDto;
import com.sddnw.server.dto.SendSignalRequest;
import com.sddnw.server.service.SignalService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Короткие знаки между людьми партии — field-backlog, пункт 183. */
@RestController
@RequestMapping("/api/games/{gameId}/signals")
public class SignalController {

    private final SignalService signals;
    private final com.sddnw.server.service.ProblemReportService problemReports;

    public SignalController(SignalService signals, com.sddnw.server.service.ProblemReportService problemReports) {
        this.signals = signals;
        this.problemReports = problemReports;
    }

    /** Последние знаки: вам, всем и ваши. */
    @GetMapping
    public List<GameSignalDto> recent(@PathVariable UUID gameId, @AccessToken String accessToken) {
        return signals.recent(gameId, accessToken);
    }

    /** Кого игрок выключил — пункт 16. */
    @GetMapping("/muted")
    public List<UUID> muted(@PathVariable UUID gameId, @AccessToken String accessToken) {
        return signals.muted(gameId, accessToken);
    }

    /** Выключить или вернуть сообщения игрока — пункт 16. */
    @org.springframework.web.bind.annotation.PutMapping("/muted/{playerId}")
    public List<UUID> mute(@PathVariable UUID gameId, @PathVariable UUID playerId,
                           @org.springframework.web.bind.annotation.RequestParam Boolean muted,
                           @AccessToken String accessToken) {
        return signals.mute(gameId, accessToken, playerId, muted);
    }

    /** Жалоба на реплику — пункт 16: ложится в «Сообщения о проблемах». */
    @PostMapping("/{signalId}/report")
    @org.springframework.web.bind.annotation.ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void report(@PathVariable UUID gameId, @PathVariable UUID signalId, @AccessToken String accessToken,
                       @AccountToken String accountToken) {
        signals.report(gameId, accessToken, accountToken, signalId, problemReports);
    }

    /** Отправить готовую реплику одному человеку или всем. */
    @PostMapping
    public List<GameSignalDto> send(@PathVariable UUID gameId, @Valid @RequestBody SendSignalRequest request) {
        return signals.send(gameId, request);
    }
}
