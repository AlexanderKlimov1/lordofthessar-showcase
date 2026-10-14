package com.sddnw.server.web;

import com.sddnw.server.dto.CreateGameResponse;
import com.sddnw.server.dto.InviteDto;
import com.sddnw.server.dto.JoinGameRequest;
import com.sddnw.server.service.AccountService;
import com.sddnw.server.service.InviteService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** Приглашение в партию по ссылке — field-backlog, пункты 15 и 61. */
@RestController
public class InviteController {

    private final InviteService invites;
    private final AccountService accountService;

    public InviteController(InviteService invites, AccountService accountService) {
        this.invites = invites;
        this.accountService = accountService;
    }

    /** Код приглашения партии — любому её участнику. */
    @GetMapping("/api/games/{gameId}/invite")
    public Map<String, String> code(@PathVariable UUID gameId, @AccessToken String accessToken) {
        return Map.of("code", invites.code(gameId, accessToken));
    }

    /** Что ждёт пришедшего — без входа тоже: экран приглашения стоит перед входом. */
    @GetMapping("/api/invites/{code}")
    public InviteDto preview(@PathVariable String code) {
        return invites.preview(code);
    }

    /** Войти по приглашению — гостю тоже (пункт 61): его позвал знакомый. */
    @PostMapping("/api/invites/{code}/join")
    public CreateGameResponse join(@PathVariable String code, @AccountToken String accountToken,
                                   @Valid @RequestBody JoinGameRequest request) {
        return invites.join(code, accountService.require(accountToken), request);
    }
}
