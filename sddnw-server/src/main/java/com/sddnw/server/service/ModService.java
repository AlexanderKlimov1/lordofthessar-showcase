package com.sddnw.server.service;

import com.sddnw.server.domain.entity.GameModEntity;
import com.sddnw.server.domain.entity.history.ModSetEntity;
import com.sddnw.server.dto.ModSetDto;
import com.sddnw.server.dto.SaveModSetRequest;
import com.sddnw.server.repository.GameModRepository;
import com.sddnw.server.repository.history.ModSetRepository;
import com.sddnw.server.web.error.BadRequestException;
import com.sddnw.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Моды — свои наборы справочников основателя, field-backlog, пункт 42 (09.10.2026, решение хозяина
 * проекта: только для пакета основателя).
 * <p>
 * Набор меняет четыре справочника — здания, корабли, технологии и лидеров; справочник рас не
 * меняет: по нему меряет цены прибор балансировки и его правит редактор цен. Набор проверяется
 * ПРИ СОХРАНЕНИИ тем же разбором, каким игра читает свои файлы ({@code check} справочников): набор,
 * который игра не прочтёт, не должен дожить до партии и уронить её первый ход.
 * <p>
 * Партия на наборе — одиночная и в историю не идёт (см. {@code GameService.createGame}): у всех за
 * одним столом должны быть одни правила, а цены и сравнения мерены на справочниках игры.
 */
@Service
public class ModService {

    private static final Logger log = LoggerFactory.getLogger(ModService.class);

    /** Предел одного текста набора: справочники игры занимают десятки килобайт, запас вдесятеро. */
    static final int TEXT_LIMIT = 2_000_000;

    private final ModSetRepository sets;
    private final GameModRepository gameMods;
    private final BuildingCatalog buildings;
    private final ShipCatalog ships;
    private final ResearchCatalog technologies;
    private final LeaderCatalog leaders;
    private final com.sddnw.server.config.GameProperties gameProperties;

    public ModService(ModSetRepository sets, GameModRepository gameMods, BuildingCatalog buildings,
                      ShipCatalog ships, ResearchCatalog technologies, LeaderCatalog leaders,
                      com.sddnw.server.config.GameProperties gameProperties) {
        this.gameProperties = gameProperties;
        this.sets = sets;
        this.gameMods = gameMods;
        this.buildings = buildings;
        this.ships = ships;
        this.technologies = technologies;
        this.leaders = leaders;
    }

    @Transactional(value = "historyTransactionManager", readOnly = true)
    public List<ModSetDto> list(UUID accountId) {
        return sets.findAllByAccountIdOrderByNameAsc(accountId).stream().map(ModService::dto).toList();
    }

    @Transactional("historyTransactionManager")
    public ModSetDto save(UUID accountId, SaveModSetRequest request) {
        if (blank(request.buildings()) && blank(request.ships()) && blank(request.technologies())
                && blank(request.leaders())) {
            throw new BadRequestException("mods.empty");
        }
        check("mods.part.buildings", request.buildings(), buildings::check);
        check("mods.part.ships", request.ships(), ships::check);
        check("mods.part.technologies", request.technologies(), technologies::check);
        check("mods.part.leaders", request.leaders(), leaders::check);
        ModSetEntity set = new ModSetEntity();
        set.setAccountId(accountId);
        set.setName(request.name().strip());
        set.setBuildings(blank(request.buildings()) ? null : request.buildings());
        set.setShips(blank(request.ships()) ? null : request.ships());
        set.setTechnologies(blank(request.technologies()) ? null : request.technologies());
        set.setLeaders(blank(request.leaders()) ? null : request.leaders());
        set.setUpdatedAt(OffsetDateTime.now());
        sets.save(set);
        log.info("Запись {} сохранила набор справочников «{}»", accountId, set.getName());
        return dto(set);
    }

    @Transactional("historyTransactionManager")
    public void delete(UUID accountId, UUID setId) {
        ModSetEntity set = own(accountId, setId);
        sets.delete(set);
    }

    /**
     * Файл справочника игры как есть — основа набора (пункт 42): набор собирают, правя файл игры, и
     * без него собрать его нечем.
     */
    public String baseText(String part) {
        String path = switch (part) {
            case "buildings" -> gameProperties.buildingsFile();
            case "ships" -> gameProperties.shipsFile();
            case "technologies" -> gameProperties.techFile();
            case "leaders" -> gameProperties.leadersFile();
            default -> throw new BadRequestException("mods.notFound", part);
        };
        try {
            return java.nio.file.Files.readString(java.nio.file.Path.of(path).toAbsolutePath());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** Набор записи — только своей; чужой не виден вовсе. */
    @Transactional(value = "historyTransactionManager", readOnly = true)
    public ModSetEntity own(UUID accountId, UUID setId) {
        return sets.findById(setId)
                .filter(set -> set.getAccountId().equals(accountId))
                .orElseThrow(() -> new NotFoundException("mods.notFound", setId));
    }

    /** Копия набора партии — при её создании: дальше партия от набора записи не зависит. */
    public void attach(UUID gameId, ModSetEntity set) {
        GameModEntity copy = new GameModEntity();
        copy.setGameId(gameId);
        copy.setName(set.getName());
        copy.setBuildings(set.getBuildings());
        copy.setShips(set.getShips());
        copy.setTechnologies(set.getTechnologies());
        copy.setLeaders(set.getLeaders());
        gameMods.save(copy);
    }

    private static void check(String part, String text, Consumer<String> parser) {
        if (blank(text)) {
            return;
        }
        if (text.length() > TEXT_LIMIT) {
            throw new BadRequestException("mods.tooLarge", new MessageKey(part));
        }
        try {
            parser.accept(text);
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String reason = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
            throw new BadRequestException("mods.invalid", new MessageKey(part),
                    reason.length() > 300 ? reason.substring(0, 300) + "…" : reason);
        }
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    private static ModSetDto dto(ModSetEntity set) {
        return new ModSetDto(set.getId(), set.getName(), set.getBuildings() != null, set.getShips() != null,
                set.getTechnologies() != null, set.getLeaders() != null, set.getUpdatedAt());
    }
}
