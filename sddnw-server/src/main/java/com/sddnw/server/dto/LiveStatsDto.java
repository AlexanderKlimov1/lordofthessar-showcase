package com.sddnw.server.dto;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Признаки живой игры — field-backlog, пункт 45; только администратору и модератору.
 *
 * @param weekGames       партий доиграно за неделю (людьми, без учебных и служебных)
 * @param onlineNow       людей сейчас в сетевых партиях
 * @param publicThreshold ниже скольких людей сейчас блок игрокам не показывался бы
 * @param onlineMinutes   за сколько минут действие считается «сейчас»
 * @param lastGames       последние законченные партии
 */
public record LiveStatsDto(Integer weekGames, Integer onlineNow, Integer publicThreshold, Integer onlineMinutes,
                           List<LastGame> lastGames) {

    /**
     * @param raceName    раса лучшего из людей партии
     * @param outcome     его исход: WON, LOST…
     * @param victoryKind путь к победе; пусто — партия не кончилась победой
     * @param turns       ходов сыграно
     * @param empires     империй за столом
     * @param humans      людей за столом
     */
    public record LastGame(String raceName, String outcome, String victoryKind, Integer turns, Integer empires,
                           Integer humans, OffsetDateTime finishedAt) {
    }
}
