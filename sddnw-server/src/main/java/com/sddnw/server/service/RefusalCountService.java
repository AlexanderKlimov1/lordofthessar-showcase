package com.sddnw.server.service;

import com.sddnw.server.domain.entity.history.RefusalCountEntity;
import com.sddnw.server.dto.RefusalCountDto;
import com.sddnw.server.repository.history.RefusalCountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Счётчик частых отказов — field-backlog, пункт 77.
 * <p>
 * Считает {@code GlobalExceptionHandler}: там каждый отказ API уже проходит одним путём и с
 * ключом. <b>Считаются только живые игроки</b> — запрос из браузера. Сквозной прогон, балансовые
 * и учебные скрипты ходят к серверу стандартной библиотекой питона и нарочно собирают отказы
 * сотнями; их опознаёт заголовок {@code User-Agent} ({@code Python-urllib}), а ИИ отказов через
 * API не получает вовсе — он ходит изнутри хода.
 * <p>
 * <b>Копится в памяти, а в базу сбрасывается раз в минуту</b>: отказ — ответ на запрос, который
 * и так не удался, и своя запись в базу на каждый из них превратила бы частый отказ в частую
 * запись. Потеря последней минуты при остановке сервера ответа «где игроки спотыкаются» не меняет.
 */
@Service
public class RefusalCountService {

    private static final Logger log = LoggerFactory.getLogger(RefusalCountService.class);

    /** Скрипты проекта: стандартная библиотека питона представляется так. */
    private static final String SCRIPT_AGENT = "Python-urllib";

    private final RefusalCountRepository counts;
    private final AccountService accountService;
    private final Messages messages;

    private final Map<String, AtomicLong> pending = new ConcurrentHashMap<>();
    private final Map<String, OffsetDateTime> lastAt = new ConcurrentHashMap<>();

    public RefusalCountService(RefusalCountRepository counts, AccountService accountService,
                               Messages messages) {
        this.counts = counts;
        this.accountService = accountService;
        this.messages = messages;
    }

    /** Отказ с этим ключом достался запросу с этим {@code User-Agent}. */
    public void count(String key, String userAgent) {
        if (key == null || userAgent == null || userAgent.startsWith(SCRIPT_AGENT)) {
            return;
        }
        pending.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
        lastAt.put(key, OffsetDateTime.now());
    }

    /** Накопленное — в базу. */
    @Scheduled(initialDelay = 60_000, fixedDelay = 60_000)
    @Transactional("historyTransactionManager")
    public void flush() {
        if (pending.isEmpty()) {
            return;
        }
        Map<String, Long> taken = new HashMap<>();
        pending.forEach((key, value) -> {
            long n = value.getAndSet(0);
            if (n > 0) {
                taken.put(key, n);
            }
        });
        pending.values().removeIf(value -> value.get() == 0);
        taken.forEach((key, n) -> {
            RefusalCountEntity row = counts.findById(key).orElseGet(() -> {
                RefusalCountEntity created = new RefusalCountEntity();
                created.setMessageKey(key);
                return created;
            });
            row.setRefusals(row.getRefusals() + n);
            row.setLastAt(lastAt.getOrDefault(key, OffsetDateTime.now()));
            counts.save(row);
        });
        log.debug("Отказы игроков записаны: {}", taken);
    }

    /** Частые отказы — только администратору; сначала сбрасывается накопленное. */
    @Transactional("historyTransactionManager")
    public List<RefusalCountDto> list(String accountToken) {
        accountService.requireAdmin(accountToken);
        flush();
        return counts.findAllByOrderByRefusalsDescMessageKeyAsc().stream()
                .map(row -> new RefusalCountDto(row.getMessageKey(), messages.get(row.getMessageKey()),
                        row.getRefusals(), row.getLastAt()))
                .toList();
    }
}
