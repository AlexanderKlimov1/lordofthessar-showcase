package com.sddnw.server.web;

import com.sddnw.server.dto.NoticeDto;
import com.sddnw.server.dto.ReleaseDto;
import com.sddnw.server.service.NoticeService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * «Что нового» и объявления — field-backlog, пункты 159 и 161: номер версии, весь список,
 * непрочитанное и «прочитал».
 */
@RestController
@RequestMapping("/api/notices")
public class NoticeController {

    private final NoticeService notices;

    public NoticeController(NoticeService notices) {
        this.notices = notices;
    }

    @GetMapping
    public List<NoticeDto> unseen(@AccountToken String accountToken) {
        return notices.unseen(accountToken);
    }

    /** Номер версии — главное меню показывает его и до входа. */
    @GetMapping("/release")
    public ReleaseDto release() {
        return notices.release();
    }

    /** Весь список «что нового», свежее сверху. */
    @GetMapping("/all")
    public List<NoticeDto> all() {
        return notices.all();
    }

    @PostMapping("/seen")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void seen(@AccountToken String accountToken) {
        notices.seen(accountToken);
    }
}
