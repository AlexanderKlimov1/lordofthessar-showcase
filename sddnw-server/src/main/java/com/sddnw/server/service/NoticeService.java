package com.sddnw.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sddnw.server.domain.LocalizedText;
import com.sddnw.server.domain.entity.history.AccountEntity;
import com.sddnw.server.dto.NoticeDto;
import com.sddnw.server.dto.ReleaseDto;
import com.sddnw.server.repository.history.AccountRepository;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * «Что нового» и объявления об изменениях — field-backlog, пункты 159 и 161: изменения,
 * которые касаются игрока, не происходят молча.
 * <p>
 * Миграция, переименование ключей браузера, сброс партий, правка цен, делающая расу
 * незаконной, — всё это объявляется: что потеряно или сдвинулось и что делать (пункт 161). А
 * после каждой выкладки — короткий список словами игрока: что исправлено, что изменилось в
 * правилах, какие цены сторон рас сдвинулись и почему (пункт 159). Это одна и та же запись
 * разных разделов ({@link #KINDS}), поэтому и список один — {@code notices.json} сервера:
 * он выходит вместе со сборкой, которая и меняет.
 * <p>
 * Там же лежит НОМЕР ВЕРСИИ ({@code version}): он стоит в главном меню и уходит с каждым
 * сообщением о проблеме. Номер живёт рядом со списком нарочно — выкладка, о которой есть что
 * сказать игроку, и есть новая версия, и разъехаться им негде.
 * <p>
 * Учётная запись помнит, когда видела список последний раз ({@code account.notices_seen_at},
 * миграция 099). Показывается только новое: вышедшее после последнего взгляда и после
 * заведения записи — у записи, заведённой позже, ничего не отнимали, и прошлые выкладки ей
 * незачем. Запись с датой в будущем ждёт своей даты: дата — это миг выкладки.
 */
@Service
public class NoticeService {

    /** Разделы «что нового» — в этом порядке их и показывают: потерянное прежде нового. */
    public static final List<String> KINDS = List.of("loss", "changed", "prices", "new", "fixed");

    private record Notice(String id, OffsetDateTime date, String version, String kind, LocalizedText text) {
    }

    private final AccountService accountService;
    private final AccountRepository accounts;
    private final String version;
    private final List<Notice> notices;

    public NoticeService(AccountService accountService, AccountRepository accounts, ObjectMapper mapper) {
        this.accountService = accountService;
        this.accounts = accounts;
        try (InputStream in = new ClassPathResource("notices.json").getInputStream()) {
            JsonNode root = mapper.readTree(in);
            this.version = root.path("version").asText();
            this.notices = read(root, version);
        } catch (IOException cause) {
            throw new UncheckedIOException("Не удалось прочитать notices.json", cause);
        }
    }

    /**
     * Список читается один раз: он выходит со сборкой и до следующей не меняется. Неверная
     * запись — отказ при старте, а не молчание: раздел с опечаткой просто не показался бы,
     * а версия новее текущей значила бы, что номер в меню забыли поднять.
     */
    private static List<Notice> read(JsonNode root, String current) {
        if (current.isBlank()) {
            throw new IllegalStateException("notices.json: не задан номер версии (version)");
        }
        Set<String> kinds = Set.copyOf(KINDS);
        List<Notice> list = new ArrayList<>();
        for (JsonNode node : root.path("notices")) {
            String id = node.path("id").asText();
            String kind = node.path("kind").asText();
            String version = node.path("version").asText();
            if (!kinds.contains(kind)) {
                throw new IllegalStateException("notices.json: у записи " + id + " неизвестный раздел " + kind);
            }
            if (version.isBlank() || compareVersions(version, current) > 0) {
                throw new IllegalStateException("notices.json: у записи " + id + " версия " + version
                        + " пуста или новее текущей " + current);
            }
            list.add(new Notice(id, OffsetDateTime.parse(node.path("date").asText()), version, kind,
                    LocalizedText.of(node.path("text"))));
        }
        list.sort(Comparator.comparing(Notice::date));
        return List.copyOf(list);
    }

    /** Сравнение номеров версий по числам: «0.10» новее «0.9», хотя строкой выходит наоборот. */
    static int compareVersions(String left, String right) {
        String[] a = left.split("[.+-]");
        String[] b = right.split("[.+-]");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length && a[i].matches("\\d+") ? Integer.parseInt(a[i]) : 0;
            int y = i < b.length && b[i].matches("\\d+") ? Integer.parseInt(b[i]) : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    /** Номер текущей версии — для меню и сообщений о проблеме. */
    public ReleaseDto release() {
        return new ReleaseDto(version);
    }

    /** Номер текущей версии строкой — им по умолчанию помечается ответ «исправлено». */
    public String version() {
        return version;
    }

    /** Объявления, которых эта запись ещё не видела, — по порядку выхода. */
    @Transactional(value = "historyTransactionManager", readOnly = true)
    public List<NoticeDto> unseen(String accountToken) {
        AccountEntity account = accountService.require(accountToken);
        OffsetDateTime since = account.getNoticesSeenAt() == null
                || account.getNoticesSeenAt().isBefore(account.getCreatedAt())
                ? account.getCreatedAt() : account.getNoticesSeenAt();
        OffsetDateTime now = OffsetDateTime.now();
        return notices.stream()
                .filter(notice -> notice.date().isAfter(since) && !notice.date().isAfter(now))
                .map(NoticeService::toDto)
                .toList();
    }

    /**
     * Весь список «что нового», свежее сверху, — по ссылке с номером версии в меню. Учётной
     * записи не спрашивает: это история игры, а не чья-то.
     */
    public List<NoticeDto> all() {
        OffsetDateTime now = OffsetDateTime.now();
        return notices.stream()
                .filter(notice -> !notice.date().isAfter(now))
                .sorted(Comparator.comparing(Notice::date).reversed())
                .map(NoticeService::toDto)
                .toList();
    }

    /** Запись прочитала объявления — до следующего выхода их больше не показывают. */
    @Transactional("historyTransactionManager")
    public void seen(String accountToken) {
        AccountEntity account = accountService.require(accountToken);
        account.setNoticesSeenAt(OffsetDateTime.now());
        accounts.save(account);
    }

    private static NoticeDto toDto(Notice notice) {
        return new NoticeDto(notice.id(), notice.date(), notice.version(), notice.kind(),
                notice.text().text(LocaleContextHolder.getLocale()));
    }
}
