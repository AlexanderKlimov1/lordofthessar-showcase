package com.sddnw.server.service;

import com.sddnw.server.domain.entity.StewardDecisionEntity;
import com.sddnw.server.dto.StewardDecisionDto;
import com.sddnw.server.repository.StewardDecisionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Что ИИ сделал за вас — field-backlog, пункт 112.
 * <p>
 * Ушедшего в партии с людьми ведёт ИИ ({@link PresenceService#aiDriven}), и вернувшийся видит
 * чужие решения как свои — империю он не узнаёт, а потерю флота читает как свою ошибку. Поэтому
 * каждое решение опекуна пишется строкой (встреча флотов, заложенная стройка, отправленный флот,
 * цель науки), а вернувшийся читает их разом и отмечает прочитанными. Пишется только за людей:
 * у соседа ИИ читателя нет.
 * <p>
 * Пишется изнутри посчитанного хода, но это только вставки и только за ушедших — выборок «на
 * игрока» здесь нет.
 */
@Service
public class StewardService {

    /** Сколько решений хранить на игрока: отлучка в сотню ходов не должна копить тысячи строк. */
    static final int MAX_SHOWN = 200;

    private final StewardDecisionRepository repository;
    private final Messages messages;
    private final CatalogTexts catalogTexts;

    public StewardService(StewardDecisionRepository repository, Messages messages, CatalogTexts catalogTexts) {
        this.repository = repository;
        this.messages = messages;
        this.catalogTexts = catalogTexts;
    }

    /** Записать решение опекуна. */
    public void record(UUID gameId, UUID playerId, Integer turn, MessageKey message) {
        StewardDecisionEntity row = new StewardDecisionEntity();
        row.setGameId(gameId);
        row.setPlayerId(playerId);
        row.setTurn(turn);
        row.setMessageKey(message.key());
        row.setArgs(message.storedArgs());
        repository.save(row);
    }

    /**
     * Решения за время отсутствия на языке читателя — последние {@link #MAX_SHOWN}: ссылки на
     * справочник становятся названиями, хранимые имена империй и проектов — словами читателя.
     */
    @Transactional(readOnly = true)
    public List<StewardDecisionDto> decisions(UUID playerId) {
        List<StewardDecisionEntity> rows = repository.findAllByPlayerIdOrderByTurnAsc(playerId);
        return rows.subList(Math.max(0, rows.size() - MAX_SHOWN), rows.size()).stream()
                .map(row -> new StewardDecisionDto(row.getTurn(), messages.event(row.getMessageKey(),
                        row.getArgs() == null ? List.of() : row.getArgs().stream().map(catalogTexts::resolve).toList())))
                .toList();
    }

    /** Вернувшийся прочёл: список начинается заново со следующей отлучки. */
    @Transactional
    public void clear(UUID playerId) {
        repository.deleteAllByPlayerId(playerId);
    }
}
