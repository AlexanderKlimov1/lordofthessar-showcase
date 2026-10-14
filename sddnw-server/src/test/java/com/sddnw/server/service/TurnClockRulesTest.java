package com.sddnw.server.service;

import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.enums.PlayerType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Часы хода и уход из-за стола — backlog-promo, пункт 11.
 */
class TurnClockRulesTest {

    private static final OffsetDateTime START = OffsetDateTime.parse("2026-10-02T12:00:00Z");

    @Test
    @DisplayName("Годятся только сроки окна новой игры — или без срока")
    void allowedSeconds() {
        assertTrue(TurnClockRules.allowed(null));
        assertTrue(TurnClockRules.allowed(90));
        assertTrue(TurnClockRules.allowed(600));
        assertFalse(TurnClockRules.allowed(1), "срок в секунду значил бы партию, которую не сыграть");
    }

    @Test
    @DisplayName("ИИ берёт империю по безделью, а на последнем пропущенном сроке — раньше, если он ближе")
    void takeoverAt() {
        OffsetDateTime acted = START.plusSeconds(30);
        assertEquals(acted.plus(TurnClockRules.IDLE), TurnClockRules.takeoverAt(acted, 0, START, null),
                "без срока хода — только безделье");
        assertEquals(acted.plus(TurnClockRules.IDLE), TurnClockRules.takeoverAt(acted, 0, START, 90),
                "срок не последний: пропуск не передаёт империю");
        assertEquals(START.plusSeconds(90),
                TurnClockRules.takeoverAt(acted, TurnClockRules.MISSED_LIMIT - 1, START, 90),
                "последний пропущенный срок ближе безделья — он и решает");
        assertEquals(acted.plus(TurnClockRules.IDLE),
                TurnClockRules.takeoverAt(acted, TurnClockRules.MISSED_LIMIT - 1, START, 600),
                "срок дальше безделья — решает безделье");
    }

    @Test
    @DisplayName("Срок истекает ровно через назначенное число секунд от начала хода")
    void expiresOnTime() {
        assertFalse(TurnClockRules.expired(START, 90, START.plusSeconds(89)));
        assertTrue(TurnClockRules.expired(START, 90, START.plusSeconds(90)));
        assertFalse(TurnClockRules.expired(START, null, START.plusSeconds(100_000)), "без срока не истекает");
        assertNull(TurnClockRules.deadline(START, null));
    }

    @Test
    @DisplayName("Три минуты без действия — ушёл")
    void idleAfterThreeMinutes() {
        assertFalse(TurnClockRules.idle(START, START.plusSeconds(179), null));
        assertTrue(TurnClockRules.idle(START, START.plusSeconds(180), null));
        // Медленная партия (пункт 64): ушедшим называют только пропущенные сроки, а не три минуты.
        assertFalse(TurnClockRules.idle(START, START.plusSeconds(7200), 4 * 3600));
        assertNull(TurnClockRules.takeoverAt(START, 0, START, 4 * 3600));
        assertEquals(START.plusSeconds(24 * 3600),
                TurnClockRules.takeoverAt(START, TurnClockRules.MISSED_LIMIT - 1, START, 24 * 3600));
    }

    @Test
    @DisplayName("Ушедшего человека ведёт ИИ, вернувшегося — снова он сам; три пропуска подряд считаются")
    void presenceDrivesAi() {
        PresenceService presence = new PresenceService();
        PlayerEntity human = new PlayerEntity();
        human.setId(UUID.randomUUID());
        human.setPlayerType(PlayerType.HUMAN);

        assertFalse(presence.aiDriven(human));
        presence.leave(human.getId());
        assertTrue(presence.aiDriven(human), "ушедшего ведёт ИИ");
        presence.act(human.getId());
        assertFalse(presence.aiDriven(human), "любое действие возвращает место");

        assertEquals(1, presence.miss(human.getId()));
        assertEquals(2, presence.miss(human.getId()));
        presence.act(human.getId());
        assertEquals(1, presence.miss(human.getId()), "действие обнуляет счёт пропусков");
    }
}
