package com.sddnw.server.web;

import com.sddnw.server.dto.ShipTemplateDto;
import com.sddnw.server.service.AccountService;
import com.sddnw.server.service.ShipTemplateService;
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
 * Библиотека проектов кораблей — field-backlog, пункт 96. Адрес вне партии: проект принадлежит
 * учётной записи и переживает партию, как шаблоны стройки.
 */
@RestController
@RequestMapping("/api/ship-templates")
public class ShipTemplateController {

    private final ShipTemplateService templates;
    private final AccountService accountService;

    public ShipTemplateController(ShipTemplateService templates, AccountService accountService) {
        this.templates = templates;
        this.accountService = accountService;
    }

    @GetMapping
    public List<ShipTemplateDto> list(@AccountToken String accountToken) {
        return templates.list(accountService.require(accountToken).getId());
    }

    /** Новый проект библиотеки или правка своего: пустой `id` — новый. */
    @PostMapping
    public ShipTemplateDto save(@Valid @RequestBody ShipTemplateDto request, @AccountToken String accountToken) {
        return templates.save(accountService.require(accountToken).getId(), request);
    }

    @DeleteMapping("/{templateId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID templateId, @AccountToken String accountToken) {
        templates.delete(accountService.require(accountToken).getId(), templateId);
    }
}
