package com.sddnw.server.web;

import com.sddnw.server.dto.GameHistoryDto;
import com.sddnw.server.service.AccountService;
import com.sddnw.server.service.GameRecordService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** История своих партий — field-backlog, пункт 18: по учётной записи, без партии. */
@RestController
public class GameHistoryController {

    private final AccountService accountService;
    private final GameRecordService records;

    public GameHistoryController(AccountService accountService, GameRecordService records) {
        this.accountService = accountService;
        this.records = records;
    }

    @GetMapping("/api/auth/game-history")
    public GameHistoryDto history(@AccountToken String accountToken) {
        return records.historyOf(accountService.require(accountToken).getId());
    }
}
