package com.sddnw.server.web;

import com.sddnw.server.dto.AdviceDto;
import com.sddnw.server.service.AdvisorService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Советник — field-backlog, пункт 23: что выбрал бы ИИ на месте игрока, только чтение. */
@RestController
@RequestMapping("/api/games/{gameId}/advice")
public class AdvisorController {

    private final AdvisorService advisor;

    public AdvisorController(AdvisorService advisor) {
        this.advisor = advisor;
    }

    @GetMapping
    public AdviceDto advice(@PathVariable UUID gameId, @AccessToken String accessToken) {
        return advisor.advice(gameId, accessToken);
    }
}
