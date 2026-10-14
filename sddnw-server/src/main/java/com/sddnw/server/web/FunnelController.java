package com.sddnw.server.web;

import com.sddnw.server.dto.FunnelStepDto;
import com.sddnw.server.dto.FunnelStepRequest;
import com.sddnw.server.service.FunnelService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Воронка новичка — field-backlog, пункт 2: шаги пути пишет клиент, читает администратор.
 * <p>
 * Пропуск записи при записи шага НЕобязателен — и потому берётся заголовком напрямую, а не
 * {@link AccountToken}: первый шаг воронки случается до всякого входа.
 */
@RestController
@RequestMapping("/api/funnel")
public class FunnelController {

    private final FunnelService funnel;

    public FunnelController(FunnelService funnel) {
        this.funnel = funnel;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void record(@Valid @RequestBody FunnelStepRequest request,
                       @RequestHeader(value = AccountTokenResolver.HEADER, required = false) String accountToken) {
        funnel.record(request, accountToken);
    }

    /** Шаги за последние дни — для {@code tools/guest_funnel.py}; только администратору. */
    @GetMapping
    public List<FunnelStepDto> steps(@AccountToken String accountToken,
                                     @RequestParam(required = false) Integer days) {
        return funnel.steps(accountToken, days);
    }
}
