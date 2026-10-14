package com.sddnw.server.service;

import com.sddnw.server.domain.entity.GameModEntity;
import com.sddnw.server.repository.GameModRepository;
import com.sddnw.server.repository.GameRepository;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Наборы справочников партий — field-backlog, пункт 42: что отдать справочнику, когда поток
 * занят партией с набором ({@link ModScope}).
 * <p>
 * Копия набора у партии не меняется никогда (правила начатой партии посреди игры не правят),
 * поэтому она читается из базы ОДИН раз и дальше живёт в памяти — справочники спрашивают её на
 * каждое обращение, а выборка изнутри посчитанного хода стоит дорого (грабли правил проекта). Признак
 * «у партии есть набор» кэшируется тем же правилом: он ставится при создании и не снимается.
 */
@Component
public class GameMods {

    /** Справочник, который подменяет набор. */
    public enum Kind { BUILDINGS, SHIPS, TECHNOLOGIES, LEADERS }

    private final GameModRepository mods;
    private final GameRepository games;
    private final Map<UUID, Optional<GameModEntity>> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> modded = new ConcurrentHashMap<>();

    public GameMods(GameModRepository mods, GameRepository games) {
        this.mods = mods;
        this.games = games;
    }

    /** Есть ли у партии свой набор — по нему перехватчик решает, ставить ли отметку. */
    public Boolean modded(UUID gameId) {
        return modded.computeIfAbsent(gameId, id -> games.findById(id)
                .map(game -> Boolean.TRUE.equals(game.getModded()))
                .orElse(Boolean.FALSE));
    }

    /** Текст справочника из набора партии; {@code null} — этот справочник у партии обычный. */
    public String content(UUID gameId, Kind kind) {
        GameModEntity mod = cache.computeIfAbsent(gameId, mods::findById).orElse(null);
        if (mod == null) {
            return null;
        }
        String text = switch (kind) {
            case BUILDINGS -> mod.getBuildings();
            case SHIPS -> mod.getShips();
            case TECHNOLOGIES -> mod.getTechnologies();
            case LEADERS -> mod.getLeaders();
        };
        return text == null || text.isBlank() ? null : text;
    }

    /** Партия удалена — её набор из памяти тоже. */
    public void forget(UUID gameId) {
        cache.remove(gameId);
        modded.remove(gameId);
    }
}
