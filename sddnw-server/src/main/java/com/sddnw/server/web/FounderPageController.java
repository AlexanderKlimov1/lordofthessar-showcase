package com.sddnw.server.web;

import com.sddnw.server.dto.FounderPageDto;
import com.sddnw.server.service.FounderPageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Страница пакета основателя: читают все, и без входа тоже, — её показывают тем, кто ещё
 * решает; правит только администратор.
 */
@RestController
@RequestMapping("/api/founders")
public class FounderPageController {

    private final FounderPageService founders;

    public FounderPageController(FounderPageService founders) {
        this.founders = founders;
    }

    /**
     * Пропуск необязателен: по нему администратор видит и неопубликованный черновик. Страницы
     * для читателя нет — 204: это не ошибка, а «ссылки в меню не будет».
     */
    @GetMapping
    public ResponseEntity<FounderPageDto> page(
            @RequestHeader(value = AccountTokenResolver.HEADER, required = false) String accountToken) {
        return founders.page(accountToken).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PutMapping
    public FounderPageDto save(@RequestBody FounderPageDto page, @AccountToken String accountToken) {
        return founders.save(accountToken, page);
    }
}
