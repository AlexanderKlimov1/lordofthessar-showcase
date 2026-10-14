package com.sddnw.server.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sddnw.server.config.GameProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Книга дебютов — field-backlog, пункт 9: лучшее ИЗМЕРЕННОЕ начало каждой расы, по которому играет
 * империя ИИ сложного уровня ({@code AiSkill.HARD}).
 * <p>
 * «Умно» здесь значит не «с подарками», а «как играют сильнейшие»: книгу собирает
 * {@code tools/ai_openings.py} из телеметрии партий ИИ — у каждой расы берётся начало империй,
 * занявших первое место по мощи к концу партии: первые постройки по порядку и разделы науки по
 * порядку первых прорывов. Дебют — порядок, а не ходы: первые здания ИИ ставит после сороковых
 * ходов, и окно «первые N ходов» оставляло книгу без единой постройки (замер 08.10.2026). Числа не вписаны руками, их выдаёт замер, и при смене
 * правил книгу пересобирают тем же инструментом.
 * <p>
 * Лежит рядом со справочниками — {@code resources/Ai/ai-openings.json}; путь выводится из пути
 * к справочнику зданий, как {@code pairs-balance.json} из пути к расам: своей настройки у файла
 * нет, и разъехаться ей не с чем. Нет файла или расы в нём — берётся общее начало ({@code
 * default}), нет и его — сложный играет как обычный. Файл перечитывается, когда меняется.
 */
@Component
public class AiOpeningBook {

    private static final Logger log = LoggerFactory.getLogger(AiOpeningBook.class);

    /**
     * Начало расы.
     *
     * @param buildings постройки родного мира по порядку — коды справочника зданий
     * @param sections  разделы науки по порядку прорывов — коды разделов дерева
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Opening(List<String> buildings, List<String> sections) {
    }

    /** Файл книги: начала по коду расы и общее. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BookFile(Map<String, Opening> races, @JsonProperty("default") Opening fallback) {
    }

    private final Path file;
    private final ObjectMapper objectMapper;
    private volatile long readAt = -1L;
    private volatile BookFile book = new BookFile(Map.of(), null);

    public AiOpeningBook(GameProperties gameProperties, ObjectMapper objectMapper) {
        this.file = Path.of(gameProperties.buildingsFile()).toAbsolutePath().getParent().getParent()
                .resolve("Ai").resolve("ai-openings.json");
        this.objectMapper = objectMapper;
    }

    /** Начало этой расы; {@code null} — книга о ней молчит и общего начала нет. */
    public Opening of(String raceCode) {
        BookFile current = current();
        Opening own = raceCode == null || current.races() == null ? null : current.races().get(raceCode);
        return own != null ? own : current.fallback();
    }

    private BookFile current() {
        try {
            if (!Files.exists(file)) {
                return new BookFile(Map.of(), null);
            }
            long modified = Files.getLastModifiedTime(file).toMillis();
            if (modified != readAt) {
                book = objectMapper.readValue(Files.readAllBytes(file), BookFile.class);
                readAt = modified;
            }
        } catch (IOException e) {
            // Книга — подсказка сильному ИИ, а не правило игры: битый файл не роняет ход, а
            // оставляет прежнюю книгу и говорит об этом в журнал.
            log.warn("Книга дебютов не прочитана: {} — {}", file, e.getMessage());
        }
        return book;
    }
}
