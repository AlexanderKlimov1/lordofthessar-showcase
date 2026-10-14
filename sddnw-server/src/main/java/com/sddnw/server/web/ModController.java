package com.sddnw.server.web;

import com.sddnw.server.dto.ModSetDto;
import com.sddnw.server.dto.SaveModSetRequest;
import com.sddnw.server.service.AccountService;
import com.sddnw.server.service.ModService;
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
 * Моды — свои наборы справочников, field-backlog, пункт 42: только основателю и администратору.
 * Адрес вне партии: набор принадлежит учётной записи, а в партию попадает копией при её создании.
 */
@RestController
@RequestMapping("/api/mods")
public class ModController {

    private final ModService mods;
    private final AccountService accountService;

    public ModController(ModService mods, AccountService accountService) {
        this.mods = mods;
        this.accountService = accountService;
    }

    @GetMapping
    public List<ModSetDto> list(@AccountToken String accountToken) {
        return mods.list(accountService.requireFounder(accountToken).getId());
    }

    /** Файл справочника игры — основа набора: buildings, ships, technologies или leaders. */
    @GetMapping(value = "/base/{part}", produces = "application/json")
    public String base(@PathVariable String part, @AccountToken String accountToken) {
        accountService.requireFounder(accountToken);
        return mods.baseText(part);
    }

    @PostMapping
    public ModSetDto save(@Valid @RequestBody SaveModSetRequest request, @AccountToken String accountToken) {
        return mods.save(accountService.requireFounder(accountToken).getId(), request);
    }

    @DeleteMapping("/{setId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID setId, @AccountToken String accountToken) {
        mods.delete(accountService.requireFounder(accountToken).getId(), setId);
    }
}
