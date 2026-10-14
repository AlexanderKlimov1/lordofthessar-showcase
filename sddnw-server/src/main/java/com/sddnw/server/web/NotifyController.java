package com.sddnw.server.web;

import com.sddnw.server.domain.entity.history.AccountEntity;
import com.sddnw.server.dto.AccountDto;
import com.sddnw.server.dto.NotifyMailRequest;
import com.sddnw.server.dto.NotifySettingsDto;
import com.sddnw.server.dto.PushSubscriptionRequest;
import com.sddnw.server.service.AccountService;
import com.sddnw.server.service.Messages;
import com.sddnw.server.service.WebPushService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Уведомления о ходе — field-backlog, пункт 22: согласие на письма, подписки браузеров и отписка
 * из письма. Адрес вне партии: согласие принадлежит учётной записи, как язык и клавиши.
 */
@RestController
@RequestMapping("/api/notify")
public class NotifyController {

    private final AccountService accountService;
    private final WebPushService push;
    private final Messages messages;

    public NotifyController(AccountService accountService, WebPushService push, Messages messages) {
        this.accountService = accountService;
        this.push = push;
        this.messages = messages;
    }

    @GetMapping
    public NotifySettingsDto settings(@AccountToken String accountToken) {
        AccountEntity account = accountService.require(accountToken);
        return new NotifySettingsDto(push.publicKey(), push.subscribed(account.getId()),
                Boolean.TRUE.equals(account.getNotifyMail()),
                account.getEmail() != null && !account.getEmail().isBlank());
    }

    @PutMapping("/mail")
    public AccountDto mail(@RequestBody NotifyMailRequest request, @AccountToken String accountToken) {
        return accountService.changeNotifyMail(accountToken, request.notifyMail());
    }

    @PostMapping("/push")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void subscribe(@Valid @RequestBody PushSubscriptionRequest request, @AccountToken String accountToken) {
        push.subscribe(accountService.require(accountToken).getId(), request.endpoint(), request.p256dh(),
                request.auth());
    }

    @DeleteMapping("/push")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unsubscribe(@RequestParam String endpoint, @AccountToken String accountToken) {
        push.unsubscribe(accountService.require(accountToken).getId(), endpoint);
    }

    /**
     * Отписка одним нажатием из письма (пункт 22): страница, а не ответ API, — её открывают из
     * почтового клиента. Входить в игру для этого не нужно: ключ в ссылке и есть право.
     */
    @GetMapping(value = "/unsubscribe/{token}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> unsubscribeMail(@PathVariable String token) {
        Boolean done = accountService.unsubscribeMail(token);
        String text = messages.get(Boolean.TRUE.equals(done) ? "notify.mail.unsubscribed" : "notify.mail.unknown");
        String page = "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" "
                + "content=\"width=device-width,initial-scale=1\"><title>" + messages.get("notify.push.titleShort")
                + "</title></head><body style=\"background:#0b0a08;color:#eadfc8;font:18px/1.5 sans-serif;"
                + "padding:48px 16px;text-align:center\"><p>" + text + "</p></body></html>";
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(page);
    }
}
