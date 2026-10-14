package com.sddnw.server.web;

import com.sddnw.server.dto.RaceTemplateDto;
import com.sddnw.server.service.AccountService;
import com.sddnw.server.service.RaceTemplateService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Свои расы — field-backlog, пункты 26 и 176. Адрес вне партии: раса принадлежит учётной записи,
 * как библиотека проектов и шаблоны стройки.
 */
@RestController
@RequestMapping("/api/race-templates")
public class RaceTemplateController {

    private final RaceTemplateService templates;
    private final AccountService accountService;

    public RaceTemplateController(RaceTemplateService templates, AccountService accountService) {
        this.templates = templates;
        this.accountService = accountService;
    }

    @GetMapping
    public List<RaceTemplateDto> list(@AccountToken String accountToken) {
        return templates.list(accountService.require(accountToken).getId());
    }

    /** Новая раса или правка своей: пустой `id` — новая. Сохраняется только законная. */
    @PostMapping
    public RaceTemplateDto save(@Valid @RequestBody RaceTemplateDto request, @AccountToken String accountToken) {
        return templates.save(accountService.require(accountToken).getId(), request);
    }

    @DeleteMapping("/{templateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID templateId, @AccountToken String accountToken) {
        templates.delete(accountService.require(accountToken).getId(), templateId);
    }
}
