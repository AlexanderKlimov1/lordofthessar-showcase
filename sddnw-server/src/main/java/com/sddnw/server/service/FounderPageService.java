package com.sddnw.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sddnw.server.domain.entity.history.AccountEntity;
import com.sddnw.server.domain.entity.history.FounderPageEntity;
import com.sddnw.server.dto.FounderPageDto;
import com.sddnw.server.repository.history.FounderPageRepository;
import com.sddnw.server.web.error.BadRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Страница пакета основателя: что получает купивший, сколько мест осталось, частые вопросы.
 * <p>
 * Читают её все, и без входа тоже: страницу показывают тем, кто ещё решает, а не тем, кто уже
 * купил. Правит только администратор, и правит целиком. Пока страница не опубликована
 * ({@code published}), её видит один администратор — черновик с недописанной ценой игрокам
 * показывать нельзя: цену на странице продажи читают как обещание.
 * <p>
 * Пока администратор не сохранил ни одной правки, отдаётся страница по умолчанию
 * ({@code founders/default-page.json}): с ней первое открытие — это готовый черновик, который
 * остаётся поправить, а не пустая форма.
 */
@Service
public class FounderPageService {

    private static final Logger log = LoggerFactory.getLogger(FounderPageService.class);

    private static final String DEFAULT_PAGE = "founders/default-page.json";

    /*
     * Пределы правки. Страницу правит один администратор, но запись — это ещё и место в базе и
     * в каждом ответе: опечатка в сотню тысяч символов не должна ехать каждому посетителю.
     * Числа взяты с запасом против страницы по умолчанию (шесть разделов, пять вопросов).
     */
    private static final int MAX_LINE = 300;
    private static final int MAX_PARAGRAPH = 2000;
    private static final int MAX_SECTIONS = 20;
    private static final int MAX_ITEMS = 30;
    private static final int MAX_QUESTIONS = 30;
    private static final int MAX_SEATS = 100_000;

    private final FounderPageRepository pages;
    private final AccountService accountService;
    private final ObjectMapper json;

    public FounderPageService(FounderPageRepository pages, AccountService accountService, ObjectMapper json) {
        this.pages = pages;
        this.accountService = accountService;
        this.json = json;
    }

    /**
     * Страница для показа. Неопубликованная — только администратору, остальным пусто: по
     * пустому ответу клиент и прячет ссылку в меню. Пусто, а не отказ: страницу спрашивает
     * каждое открытие меню, и отказ ложился бы в журнал сбоев клиента, который уходит с
     * сообщениями о проблемах, — шумом, за которым не видно настоящих сбоев.
     */
    @Transactional(value = "historyTransactionManager", readOnly = true)
    public Optional<FounderPageDto> page(String accountToken) {
        FounderPageDto page = current();
        if (!Boolean.TRUE.equals(page.published()) && !accountService.isAdmin(accountToken)) {
            return Optional.empty();
        }
        return Optional.of(page);
    }

    /** Сохранить страницу целиком — только администратору. */
    @Transactional("historyTransactionManager")
    public FounderPageDto save(String accountToken, FounderPageDto request) {
        AccountEntity admin = accountService.requireAdmin(accountToken);
        FounderPageDto clean = validated(request);
        FounderPageEntity row = pages.findById(FounderPageEntity.MAIN).orElseGet(() -> {
            FounderPageEntity created = new FounderPageEntity();
            created.setId(FounderPageEntity.MAIN);
            return created;
        });
        row.setContent(write(clean));
        row.setUpdatedBy(admin.getId());
        row.setUpdatedAt(OffsetDateTime.now());
        pages.save(row);
        log.info("Страница основателя сохранена: {} мест, занято {}, опубликована {}",
                clean.seatsTotal(), clean.seatsTaken(), clean.published());
        return withTime(clean, row.getUpdatedAt());
    }

    private FounderPageDto current() {
        return pages.findById(FounderPageEntity.MAIN)
                .map(row -> withTime(read(row.getContent()), row.getUpdatedAt()))
                .orElseGet(this::defaultPage);
    }

    private FounderPageDto defaultPage() {
        try (InputStream in = new ClassPathResource(DEFAULT_PAGE).getInputStream()) {
            return json.readValue(in, FounderPageDto.class);
        } catch (IOException failure) {
            throw new UncheckedIOException("Не удалось прочитать " + DEFAULT_PAGE, failure);
        }
    }

    /**
     * Приводит правку к хранимому виду и проверяет пределы. Пустые строки разделов и вопросов
     * выбрасываются, а не хранятся: форма правки оставляет их, когда пункт стёрт, и показывать
     * пустой пункт списком на странице продажи — небрежность, которую видит покупатель.
     */
    private FounderPageDto validated(FounderPageDto page) {
        int total = page.seatsTotal() == null ? 0 : page.seatsTotal();
        int taken = page.seatsTaken() == null ? 0 : page.seatsTaken();
        if (total < 1 || total > MAX_SEATS || taken < 0 || taken > total) {
            throw new BadRequestException("founders.seats", total, taken);
        }
        String buyUrl = trimmed(page.buyUrl());
        // Только веб-адрес: ссылка встаёт в кнопку на странице, и «javascript:» там был бы
        // чужим кодом в браузере каждого посетителя.
        if (buyUrl != null && !(buyUrl.startsWith("https://") || buyUrl.startsWith("http://"))) {
            throw new BadRequestException("founders.buyUrl");
        }
        return new FounderPageDto(
                limited(page.price(), MAX_LINE, "price"),
                total, taken,
                Boolean.TRUE.equals(page.published()),
                buyUrl == null ? null : limited(buyUrl, MAX_LINE, "buyUrl"),
                text(page.en(), "en"), text(page.ru(), "ru"), null);
    }

    private FounderPageDto.Text text(FounderPageDto.Text text, String lang) {
        if (text == null || trimmed(text.title()) == null) {
            throw new BadRequestException("founders.titleRequired", lang);
        }
        List<FounderPageDto.Section> sections = cleaned(text.sections(), MAX_SECTIONS, lang, section -> {
            List<String> items = cleaned(section.items(), MAX_ITEMS, lang,
                    item -> limited(item, MAX_PARAGRAPH, lang));
            String title = trimmed(section.title());
            return title == null && items.isEmpty() ? null
                    : new FounderPageDto.Section(limited(title, MAX_LINE, lang), items);
        });
        List<FounderPageDto.Question> faq = cleaned(text.faq(), MAX_QUESTIONS, lang, question -> {
            String asked = trimmed(question.question());
            String answer = trimmed(question.answer());
            return asked == null && answer == null ? null
                    : new FounderPageDto.Question(limited(asked, MAX_LINE, lang), limited(answer, MAX_PARAGRAPH, lang));
        });
        return new FounderPageDto.Text(
                limited(text.title(), MAX_LINE, lang),
                limited(text.tagline(), MAX_LINE, lang),
                limited(text.intro(), MAX_PARAGRAPH, lang),
                sections, faq,
                limited(text.note(), MAX_PARAGRAPH, lang));
    }

    private <T, R> List<R> cleaned(List<T> rows, int max, String lang, Function<T, R> clean) {
        List<R> kept = rows == null ? List.of()
                : rows.stream().filter(Objects::nonNull).map(clean).filter(Objects::nonNull).toList();
        if (kept.size() > max) {
            throw new BadRequestException("founders.tooMany", lang, max);
        }
        return kept;
    }

    private static String limited(String value, int max, String where) {
        String text = trimmed(value);
        if (text != null && text.length() > max) {
            throw new BadRequestException("founders.tooLong", where, max);
        }
        return text;
    }

    private static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static FounderPageDto withTime(FounderPageDto page, OffsetDateTime at) {
        return new FounderPageDto(page.price(), page.seatsTotal(), page.seatsTaken(), page.published(),
                page.buyUrl(), page.en(), page.ru(), at);
    }

    private String write(FounderPageDto page) {
        try {
            return json.writeValueAsString(page);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Страница основателя не записалась в JSON", failure);
        }
    }

    private FounderPageDto read(String content) {
        try {
            return json.readValue(content, FounderPageDto.class);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Страница основателя в базе не читается", failure);
        }
    }
}
