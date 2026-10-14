package com.sddnw.server.service;

import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.enums.PlayerType;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Кто из людей за столом, а кто ушёл — backlog-promo, пункт 11.
 * <p>
 * <b>Живёт в памяти, а не в базе, и это нарочно.</b> Отметку присутствия ставит КАЖДОЕ
 * настоящее действие игрока, а строка игрока в базе держит версию: запись из каждого
 * запроса сталкивалась бы с записью самого действия и роняла бы его на проверке версии.
 * Потеря при перезапуске сервера безвредна: все считаются присутствующими, а правило
 * безделья ({@link TurnClockRules#IDLE}) через три минуты снова отметит ушедших — счёт
 * безделья начинается с первого вопроса о игроке (см. {@link #lastAction}). Точка расширения: несколько экземпляров
 * сервера потребуют общего хранилища — сейчас партию считает один.
 * <p>
 * <b>Настоящее действие — изменяющий запрос</b> (не GET): чтение карты, отчёта и
 * подписка на события идут сами, пока вкладка открыта, и присутствия не доказывают.
 */
@Service
public class PresenceService {

    private final Map<UUID, OffsetDateTime> lastAction = new ConcurrentHashMap<>();
    private final Map<UUID, Integer> missed = new ConcurrentHashMap<>();
    private final Set<UUID> away = ConcurrentHashMap.newKeySet();

    /** Игрок что-то сделал: он за столом, и если уходил — вернулся. */
    public void act(UUID playerId) {
        lastAction.put(playerId, OffsetDateTime.now());
        missed.remove(playerId);
        away.remove(playerId);
    }

    /**
     * Когда игрок последний раз действовал. Не действовал на памяти сервера — отсчёт идёт
     * с первого вопроса: игрок, вошедший в партию до перезапуска сервера или через вход
     * без пропуска, иначе оказался бы «ушедшим» в тот же миг, как его спросили.
     */
    public OffsetDateTime lastAction(UUID playerId) {
        return lastAction.computeIfAbsent(playerId, id -> OffsetDateTime.now());
    }

    /** Игрок ушёл: сам, по безделью или по пропущенным срокам. */
    public void leave(UUID playerId) {
        away.add(playerId);
    }

    public Boolean isAway(UUID playerId) {
        return away.contains(playerId);
    }

    /**
     * Нет за столом — ушёл (память) или в отпуске (база, field-backlog, пункт 177). Все правила
     * «кого ждать» и «кого ведёт ИИ» спрашивают это, а не одно из двух.
     */
    public Boolean away(PlayerEntity player) {
        return isAway(player.getId()) || onVacation(player);
    }

    /** В отпуске сейчас: срок задан и ещё не прошёл. */
    public static Boolean onVacation(PlayerEntity player) {
        return player.getVacationUntil() != null && OffsetDateTime.now().isBefore(player.getVacationUntil());
    }

    /** Сколько сроков хода игрок пропустил подряд. */
    public Integer missed(UUID playerId) {
        return missed.getOrDefault(playerId, 0);
    }

    /**
     * Ждёт ли кто-нибудь — пункт 11: хотя бы один присутствующий человек уже закончил ход.
     * Без этого часам делать нечего: одиночная партия против ИИ не идёт сама, пока игрок
     * отошёл от стола.
     */
    public Boolean somebodyWaits(GameEntity game, List<PlayerEntity> players) {
        return players.stream()
                .filter(player -> player.getPlayerType() == PlayerType.HUMAN)
                .filter(player -> !Boolean.TRUE.equals(away(player)))
                .anyMatch(player -> player.getEndedTurn() >= game.getTurn());
    }

    /**
     * Через сколько секунд ИИ возьмёт империю — field-backlog, пункт 127: только тем, кого
     * часы и правда передадут ИИ, — присутствующим людям, ещё не закончившим ход, пока их
     * кто-то ждёт. Остальным пусто: им ничего не грозит. Сервер отдаёт срок, а не клиент
     * считает свой: правило ({@link TurnClockRules#takeoverAt}) живёт в одном месте.
     */
    public Map<UUID, Integer> takeoverSeconds(GameEntity game, List<PlayerEntity> players, OffsetDateTime now) {
        Map<UUID, Integer> seconds = new HashMap<>();
        if (!Boolean.TRUE.equals(somebodyWaits(game, players))) {
            return seconds;
        }
        for (PlayerEntity player : players) {
            if (player.getPlayerType() != PlayerType.HUMAN || Boolean.TRUE.equals(away(player))
                    || player.getEndedTurn() >= game.getTurn()) {
                continue;
            }
            OffsetDateTime at = TurnClockRules.takeoverAt(lastAction(player.getId()), missed(player.getId()),
                    game.getTurnStartedAt(), game.getTurnSeconds());
            if (at == null) {
                continue;
            }
            seconds.put(player.getId(), (int) Math.max(0, Duration.between(now, at).getSeconds()));
        }
        return seconds;
    }

    /** Ещё один пропущенный срок хода; возвращает, сколько их подряд. */
    public Integer miss(UUID playerId) {
        return missed.merge(playerId, 1, Integer::sum);
    }

    /**
     * Ведёт ли империю ИИ: соседа ИИ — всегда, человека — пока он ушёл. По этому признаку
     * решают фазы хозяйства ИИ, наука, встречи флотов и бой, а не по роду игрока: опекун
     * ушедшего — тот же ИИ, и второго свода его решений в игре нет.
     */
    public Boolean aiDriven(PlayerEntity player) {
        return player.getPlayerType() == PlayerType.AI
                || (player.getPlayerType() == PlayerType.HUMAN && away(player));
    }
}
