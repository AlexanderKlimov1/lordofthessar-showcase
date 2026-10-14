package com.sddnw.server.web;

import com.sddnw.server.dto.WeeklyGalaxyDto;
import com.sddnw.server.service.AccountService;
import com.sddnw.server.service.WeeklyGalaxyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Галактика недели — field-backlog, пункт 146: условия и итоги по расам. Заводится партия обычным
 * {@code POST /api/games} с признаком {@code weekly}: условия тогда назначает сервер.
 */
@RestController
@RequestMapping("/api/weekly")
public class WeeklyGalaxyController {

    private final WeeklyGalaxyService weekly;
    private final AccountService accounts;

    public WeeklyGalaxyController(WeeklyGalaxyService weekly, AccountService accounts) {
        this.weekly = weekly;
        this.accounts = accounts;
    }

    @GetMapping
    public WeeklyGalaxyDto current(@AccountToken String accountToken) {
        accounts.require(accountToken);
        return weekly.current();
    }
}
