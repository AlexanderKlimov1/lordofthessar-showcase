package com.sddnw.server.web;

import com.sddnw.server.dto.LiveStatsDto;
import com.sddnw.server.service.AccountService;
import com.sddnw.server.service.LiveStatsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Признаки живой игры — field-backlog, пункт 45. Пока только администратору и модератору
 * (09.10.2026, решение хозяина проекта): числа ещё малы, и игроку их не показывают.
 */
@RestController
@RequestMapping("/api/stats")
public class LiveStatsController {

    private final AccountService accountService;
    private final LiveStatsService liveStatsService;

    public LiveStatsController(AccountService accountService, LiveStatsService liveStatsService) {
        this.accountService = accountService;
        this.liveStatsService = liveStatsService;
    }

    @GetMapping("/live")
    public LiveStatsDto live(@AccountToken String accountToken) {
        accountService.requireStaff(accountToken);
        return liveStatsService.live();
    }
}
