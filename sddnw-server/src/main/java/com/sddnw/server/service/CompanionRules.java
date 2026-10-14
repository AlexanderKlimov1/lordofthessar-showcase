package com.sddnw.server.service;

import com.sddnw.server.domain.entity.history.GameRecordEntity;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Подарок за приглашение — field-backlog, пункты 50 и 174: взаимность и единство разом.
 * <p>
 * Подарок — <b>за СЫГРАННУЮ ВМЕСТЕ партию, а не за переход по ссылке и не за регистрацию друга</b>:
 * иначе ссылки раздают ради награды, друг становится средством, а приглашение — сбором чужих
 * адресов. Поэтому спутник засчитывается, когда приглашённый и позвавший ОБА доиграли одну партию
 * (признанное поражение — не доиграл). Подарок — знак спутника у имени, только облик: силы в игре
 * он не даёт (правило пункта 20). В счёт идут не больше {@link #LIMIT} спутников на запись — после
 * трёх подарки кончаются, и звать ради них незачем. Сам себя не позовёшь: позвавший и пришедший —
 * разные записи, это проверяется при записи истории. Записи с одного адреса отличить нечем — адрес
 * регистрации не хранится, — и от фермы подарков бережёт предел их числа, а не проверка адреса.
 */
public final class CompanionRules {

    /** Больше трёх подарков на запись не бывает (пункт 174). */
    public static final int LIMIT = 3;

    private CompanionRules() {
    }

    /** Доиграл: не признал поражение. */
    private static boolean finished(GameRecordEntity row) {
        return !GameRecordService.CONCEDED.equals(row.getOutcome());
    }

    /**
     * Спутники каждой записи: с кем она доиграла партию, позвав или будучи позванной, — первые
     * {@link #LIMIT} по времени партии.
     *
     * @param invited строки приглашённых ({@code invited_by} не пуст)
     * @param games   все строки тех же партий
     */
    public static Map<UUID, Set<UUID>> companions(List<GameRecordEntity> invited, List<GameRecordEntity> games) {
        Map<UUID, Set<UUID>> result = new HashMap<>();
        invited.stream().filter(CompanionRules::finished)
                .sorted(Comparator.comparing(GameRecordEntity::getFinishedAt))
                .forEach(row -> {
                    boolean together = games.stream().anyMatch(other -> other.getGameId().equals(row.getGameId())
                            && Objects.equals(other.getAccountId(), row.getInvitedBy()) && finished(other));
                    if (together) {
                        add(result, row.getInvitedBy(), row.getAccountId());
                        add(result, row.getAccountId(), row.getInvitedBy());
                    }
                });
        return result;
    }

    private static void add(Map<UUID, Set<UUID>> result, UUID who, UUID partner) {
        Set<UUID> partners = result.computeIfAbsent(who, id -> new LinkedHashSet<>());
        if (partners.size() < LIMIT) {
            partners.add(partner);
        }
    }
}
