package com.sddnw.server.web;

import com.sddnw.server.dto.RefusalCountDto;
import com.sddnw.server.service.RefusalCountService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Частые отказы игроков — field-backlog, пункт 77; только администратору. */
@RestController
@RequestMapping("/api/refusals")
public class RefusalCountController {

    private final RefusalCountService refusals;

    public RefusalCountController(RefusalCountService refusals) {
        this.refusals = refusals;
    }

    @GetMapping
    public List<RefusalCountDto> list(@AccountToken String accountToken) {
        return refusals.list(accountToken);
    }
}
