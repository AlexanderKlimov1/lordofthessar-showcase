package com.sddnw.server.service;

import com.sddnw.server.config.AuthProperties;
import com.sddnw.server.domain.entity.history.PushSubscriptionEntity;
import com.sddnw.server.domain.entity.history.ServerSecretEntity;
import com.sddnw.server.repository.history.PushSubscriptionRepository;
import com.sddnw.server.repository.history.ServerSecretRepository;
import com.sddnw.server.web.error.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Уведомления браузера, когда вкладка игры закрыта, — field-backlog, пункт 22 (Web Push).
 * <p>
 * Пока вкладка открыта, зовёт сам клиент ({@code ui/notify/turnNotify.ts}). Закрытую вкладку
 * достаёт только служба уведомлений браузера: сервер шлёт ей зашифрованное письмецо
 * ({@link WebPushCrypto}), служба будит сервис-воркер игры, и тот показывает уведомление.
 * <p>
 * <b>Адрес подписки приходит от клиента</b>, и слать по нему сервер будет сам — поэтому годятся
 * только адреса служб уведомлений браузеров ({@link #PUSH_HOSTS}) и только по https: иначе
 * подписка превращала бы сервер в посыльного по любым адресам.
 */
@Service
public class WebPushService {

    private static final Logger log = LoggerFactory.getLogger(WebPushService.class);

    /** Службы уведомлений браузеров: Chrome и Edge на Android, Firefox, Windows, Safari. */
    static final List<String> PUSH_HOSTS = List.of("fcm.googleapis.com", "push.services.mozilla.com",
            "notify.windows.com", "push.apple.com");

    /** Подписок у записи — с запасом на браузеры нескольких машин. */
    static final int MAX_SUBSCRIPTIONS = 10;

    /** Сколько служба держит уведомление для выключенного браузера: дольше суток «ваш ход» не нужен. */
    private static final int TTL_SECONDS = 24 * 60 * 60;

    private static final String PRIVATE_KEY = "vapid.private";
    private static final String PUBLIC_KEY = "vapid.public";

    private final PushSubscriptionRepository subscriptions;
    private final ServerSecretRepository secrets;
    private final AuthProperties properties;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private volatile KeyPair keys;

    public WebPushService(PushSubscriptionRepository subscriptions, ServerSecretRepository secrets,
                          AuthProperties properties) {
        this.subscriptions = subscriptions;
        this.secrets = secrets;
        this.properties = properties;
    }

    /** Открытый ключ сервера для {@code pushManager.subscribe} — base64url, несжатый. */
    @Transactional("historyTransactionManager")
    public String publicKey() {
        return WebPushCrypto.b64(WebPushCrypto.uncompressed((ECPublicKey) keys().getPublic()));
    }

    /** Подписать браузер записи; тот же адрес с другой записи переходит к этой. */
    @Transactional("historyTransactionManager")
    public void subscribe(UUID accountId, String endpoint, String p256dh, String auth) {
        if (!Boolean.TRUE.equals(allowed(endpoint)) || p256dh == null || auth == null
                || WebPushCrypto.unb64(p256dh).length != 65 || WebPushCrypto.unb64(auth).length != 16) {
            throw new BadRequestException("notify.push.bad");
        }
        PushSubscriptionEntity subscription = subscriptions.findByEndpoint(endpoint).orElse(null);
        if (subscription == null) {
            if (subscriptions.countByAccountId(accountId) >= MAX_SUBSCRIPTIONS) {
                // Старейшая уступает место: браузер, который не заходил давно, скорее всего забыт.
                subscriptions.delete(subscriptions.findAllByAccountIdOrderByCreatedAtAsc(accountId).get(0));
            }
            subscription = new PushSubscriptionEntity();
            subscription.setEndpoint(endpoint);
            subscription.setCreatedAt(OffsetDateTime.now());
        }
        subscription.setAccountId(accountId);
        subscription.setP256dh(p256dh);
        subscription.setAuth(auth);
        subscriptions.save(subscription);
    }

    @Transactional("historyTransactionManager")
    public void unsubscribe(UUID accountId, String endpoint) {
        subscriptions.findByEndpoint(endpoint)
                .filter(subscription -> subscription.getAccountId().equals(accountId))
                .ifPresent(subscriptions::delete);
    }

    @Transactional(value = "historyTransactionManager", readOnly = true)
    public Boolean subscribed(UUID accountId) {
        return subscriptions.countByAccountId(accountId) > 0;
    }

    /** Подписки записи удалённой записи — без хозяина они никому не нужны. */
    @Transactional("historyTransactionManager")
    public void deleteByAccount(UUID accountId) {
        subscriptions.deleteByAccountId(accountId);
    }

    /**
     * Отправить уведомление во все браузеры записи. Зовётся вне запроса (из потока уведомлений),
     * поэтому сбой одной службы не мешает остальным, а подписку, от которой служба отказалась
     * (404, 410), удаляет.
     *
     * @param payload JSON, который прочтёт сервис-воркер
     */
    @Transactional("historyTransactionManager")
    public void send(UUID accountId, String payload) {
        KeyPair server = keys();
        for (PushSubscriptionEntity subscription : subscriptions.findAllByAccountIdOrderByCreatedAtAsc(accountId)) {
            try {
                byte[] body = WebPushCrypto.encrypt(payload.getBytes(StandardCharsets.UTF_8),
                        WebPushCrypto.unb64(subscription.getP256dh()), WebPushCrypto.unb64(subscription.getAuth()));
                URI endpoint = URI.create(subscription.getEndpoint());
                String audience = endpoint.getScheme() + "://" + endpoint.getHost();
                String jwt = WebPushCrypto.vapidJwt(audience, "mailto:" + properties.mailFrom(),
                        OffsetDateTime.now().plusHours(12).toEpochSecond(), (ECPrivateKey) server.getPrivate());
                HttpRequest request = HttpRequest.newBuilder(endpoint)
                        .timeout(Duration.ofSeconds(15))
                        .header("TTL", String.valueOf(TTL_SECONDS))
                        .header("Content-Encoding", "aes128gcm")
                        .header("Content-Type", "application/octet-stream")
                        .header("Urgency", "normal")
                        .header("Authorization", "vapid t=" + jwt + ", k=" + publicKey())
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                        .build();
                int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status == 404 || status == 410) {
                    subscriptions.delete(subscription);
                    log.info("Подписка Web Push записи {} больше не действует ({}) — удалена", accountId, status);
                } else if (status >= 300) {
                    log.warn("Служба Web Push {} ответила {}", endpoint.getHost(), status);
                }
            } catch (Exception failure) {
                log.warn("Web Push записи {} не ушёл: {}", accountId, failure.getMessage());
            }
        }
    }

    /** Годится ли адрес подписки: https и служба уведомлений браузера. */
    static Boolean allowed(String endpoint) {
        if (endpoint == null || endpoint.length() > 1024) {
            return Boolean.FALSE;
        }
        try {
            URI uri = URI.create(endpoint);
            String host = uri.getHost();
            return "https".equals(uri.getScheme()) && host != null
                    && PUSH_HOSTS.stream().anyMatch(known -> host.equals(known) || host.endsWith("." + known));
        } catch (IllegalArgumentException broken) {
            return Boolean.FALSE;
        }
    }

    /** Пара VAPID: из базы, а при первом обращении — новая и сразу в базу. */
    private KeyPair keys() {
        KeyPair current = keys;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (keys == null) {
                ServerSecretEntity privatePart = secrets.findById(PRIVATE_KEY).orElse(null);
                ServerSecretEntity publicPart = secrets.findById(PUBLIC_KEY).orElse(null);
                if (privatePart != null && publicPart != null) {
                    keys = new KeyPair(WebPushCrypto.publicKey(WebPushCrypto.unb64(publicPart.getSecret())),
                            WebPushCrypto.privateKey(WebPushCrypto.unb64(privatePart.getSecret())));
                } else {
                    KeyPair fresh = WebPushCrypto.generate();
                    secrets.save(secret(PRIVATE_KEY, WebPushCrypto.b64(WebPushCrypto.raw((ECPrivateKey) fresh.getPrivate()))));
                    secrets.save(secret(PUBLIC_KEY, WebPushCrypto.b64(WebPushCrypto.uncompressed((ECPublicKey) fresh.getPublic()))));
                    keys = fresh;
                    log.info("Заведена пара ключей VAPID для Web Push");
                }
            }
            return keys;
        }
    }

    private static ServerSecretEntity secret(String name, String value) {
        ServerSecretEntity secret = new ServerSecretEntity();
        secret.setName(name);
        secret.setSecret(value);
        return secret;
    }
}
