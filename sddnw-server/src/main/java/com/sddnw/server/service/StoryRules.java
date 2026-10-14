package com.sddnw.server.service;

import com.sddnw.server.domain.entity.GameMilestoneEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * «Как это было» — field-backlog, пункт 143: пять-семь сильнейших строк хроники партии, с
 * которых начинается сцена конца.
 * <p>
 * Пересказывают историю, а не итог. Отбор — ПРАВИЛОМ, а не жребием: у каждого рода события
 * свой вес (переломы, войны, взятия и потери — сильнее первого знакомства), берутся сильнейшие,
 * а показываются по порядку ходов — это рассказ, а не рейтинг. Одного рода — не больше
 * {@link #PER_KIND}: семь войн подряд — это перечень, а не история. Конец партии в отбор не
 * входит: его сцена говорит сама, крупно.
 */
public final class StoryRules {

    /** Сколько строк в истории: меньше пяти — не рассказ, больше семи — снова список. */
    public static final int MAX_LINES = 7;

    /** Одного рода — не больше двух строк. */
    static final int PER_KIND = 2;

    /** Вес рода: чем сильнее поворот, тем больше. Род без веса в историю не входит. */
    static final Map<String, Integer> WEIGHT = Map.ofEntries(
            Map.entry(MilestoneService.LEAD, 10),
            Map.entry(MilestoneService.LEAD_LOST, 10),
            Map.entry(MilestoneService.COUNCIL, 9),
            Map.entry(MilestoneService.COUNCIL_REFUSED, 9),
            Map.entry(MilestoneService.EMPIRE_FALLEN, 8),
            Map.entry(MilestoneService.COLONY_TAKEN, 8),
            Map.entry(MilestoneService.COLONY_LOST, 8),
            Map.entry(MilestoneService.GUARDIANS, 7),
            Map.entry(MilestoneService.WAR, 6),
            Map.entry(MilestoneService.TREATY_BROKEN, 5),
            Map.entry(MilestoneService.MONSTER_SLAIN, 5),
            Map.entry(MilestoneService.PEACE, 4),
            Map.entry(MilestoneService.BATTLE_WON, 4),
            Map.entry(MilestoneService.TROPHY, 3),
            Map.entry(MilestoneService.CONTACT, 3),
            Map.entry(MilestoneService.COLONY_FOUNDED, 2),
            Map.entry(MilestoneService.TREATY, 2));

    private StoryRules() {
    }

    /**
     * Сильнейшие строки хроники по порядку ходов. При равном весе раньше — сильнее: начало
     * поворота важнее его повторов.
     */
    public static List<GameMilestoneEntity> story(List<GameMilestoneEntity> chronicle) {
        List<GameMilestoneEntity> ranked = chronicle.stream()
                .filter(row -> WEIGHT.containsKey(row.getKind()))
                .sorted(Comparator.comparing((GameMilestoneEntity row) -> -WEIGHT.get(row.getKind()))
                        .thenComparing(GameMilestoneEntity::getTurn))
                .toList();
        Map<String, Integer> taken = new HashMap<>();
        List<GameMilestoneEntity> chosen = new ArrayList<>();
        for (GameMilestoneEntity row : ranked) {
            if (chosen.size() >= MAX_LINES) {
                break;
            }
            int already = taken.getOrDefault(row.getKind(), 0);
            if (already >= PER_KIND) {
                continue;
            }
            taken.put(row.getKind(), already + 1);
            chosen.add(row);
        }
        chosen.sort(Comparator.comparing(GameMilestoneEntity::getTurn));
        return chosen;
    }
}
