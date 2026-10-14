package com.sddnw.server.service;

import com.sddnw.server.domain.entity.DiplomacyRelationEntity;
import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.entity.PlanetEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.enums.DiplomacyStance;
import com.sddnw.server.domain.enums.GameStatus;
import com.sddnw.server.domain.enums.PlayerType;
import com.sddnw.server.repository.DiplomacyRelationRepository;
import com.sddnw.server.service.stub.TurnPhase;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Главы партии — field-backlog, пункт 54 ({@link ChapterRules}).
 * <p>
 * Идёт ПОСЛЕДНЕЙ (порядок 21): после победы — у оконченной партии глав больше нет — и после
 * памяти о чужих колониях. Смена главы — строка итогов хода (ключ {@code turn.chapter}) и главный
 * момент летописи ({@code MilestoneService.CHAPTER}): что окончено и что дальше.
 * <p>
 * Считается всем империям, а не только людям: подсказку читают люди (летопись и так пишется только
 * им), но глава ИИ видна прогонам и замеру новизны (пункт 93), а человек, ушедший из-за стола,
 * возвращается в свою настоящую главу. Отношения — одна выборка на ход.
 */
@Service
public class ChapterPhase implements TurnPhase {

    private final DiplomacyRelationRepository relations;

    public ChapterPhase(DiplomacyRelationRepository relations) {
        this.relations = relations;
    }

    @Override
    public Integer order() {
        return 21;
    }

    @Override
    public String name() {
        return "Главы партии";
    }

    @Override
    public void apply(TurnContext context) {
        GameEntity game = context.game();
        if (game.getStatus() != GameStatus.IN_PROGRESS) {
            return;
        }
        List<PlayerEntity> empires = context.players().stream()
                .filter(player -> player.getPlayerType() != PlayerType.MONSTER)
                .toList();
        if (empires.isEmpty()) {
            return;
        }
        Map<UUID, Integer> colonies = new HashMap<>();
        for (PlanetEntity colony : context.colonies()) {
            colonies.merge(colony.getOwnerPlayerId(), 1, Integer::sum);
        }
        int all = colonies.values().stream().mapToInt(Integer::intValue).sum();
        int biggest = colonies.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        int alive = colonies.size();
        boolean finale = ChapterRules.finaleNear(WardenholdRules.turnsLeft(game),
                Boolean.TRUE.equals(game.getMightVictory()), MightVictoryRules.limit(game), context.turn(),
                biggest, all, alive);
        boolean council = ChapterRules.councilNear(Boolean.TRUE.equals(game.getCouncil()), alive, context.turn());

        Map<UUID, boolean[]> diplomacy = new HashMap<>();
        for (DiplomacyRelationEntity relation : relations.findAllByPlayerIdIn(
                empires.stream().map(PlayerEntity::getId).toList())) {
            boolean[] seen = diplomacy.computeIfAbsent(relation.getPlayerId(), id -> new boolean[2]);
            seen[0] = true;
            seen[1] |= relation.getStance() == DiplomacyStance.WAR;
        }
        for (PlayerEntity player : empires) {
            boolean[] seen = diplomacy.getOrDefault(player.getId(), new boolean[2]);
            int before = player.getChapter() == null ? ChapterRules.SETTLEMENT : player.getChapter();
            int now = ChapterRules.chapter(before, new ChapterRules.State(seen[0], seen[1], council, finale));
            if (now == before) {
                continue;
            }
            player.setChapter(now);
            String ended = "chapter." + ChapterRules.code(before);
            String next = "chapter." + ChapterRules.code(now);
            context.report().add(player.getId(), "CHAPTER", new MessageKey("turn.chapter", ended,
                    colonies.getOrDefault(player.getId(), 0), next, next + ".goal"));
        }
    }
}
