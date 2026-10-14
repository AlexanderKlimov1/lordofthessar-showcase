package com.sddnw.server.service;

import com.sddnw.server.domain.entity.EmpireHistoryEntity;
import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.entity.PlanetEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.dto.VictoryProgressDto;
import com.sddnw.server.repository.DiplomacyRelationRepository;
import com.sddnw.server.repository.EmpireHistoryRepository;
import com.sddnw.server.repository.PlanetRepository;
import com.sddnw.server.repository.PlayerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Прогресс к победе и ближайшая веха — field-backlog, пункты 51 и 11.
 * <p>
 * Обучение кончилось, до победы сотни ходов, и цель «что делать сейчас» размывается — беда
 * всякой 4X. Здесь расстояние до каждого включённого пути к победе теми же правилами, которыми
 * победа и считается ({@link CouncilRules}, {@link WardenholdRules}, {@link MightVictoryRules},
 * покорение {@code VictoryService}), а из них — одна ближайшая веха строкой для итогов хода.
 * <p>
 * <b>Только видимое.</b> Население и мощь знакомых империй игрок и так видит в «Инфо»; про
 * незнакомые известно лишь, что они есть (число империй он задал сам), — их голоса и мощь
 * сюда не входят, а прогресс называет их число.
 */
@Service
public class VictoryProgressService {

    /** За сколько ходов до созыва совет становится вехой. */
    static final int COUNCIL_SOON_TURNS = 10;

    /** За сколько ходов до итога по могуществу он становится вехой — тот же срок, что у правой панели. */
    static final int MIGHT_SOON_TURNS = 30;

    /** Во сколько раз сильнейший знакомый должен превосходить, чтобы стать вехой. */
    static final int RIVAL_RATIO = 2;

    /**
     * Финиш близко (пункт 56): за сколько ходов удержание Тессара и итог по могуществу становятся
     * событием, а не строкой панели. Удержание длится двадцать ходов, и последние пять — время
     * последней попытки; итогу по мощи нужен запас на рывок — десять.
     */
    static final int FINISH_THESSAR_TURNS = 5;
    static final int FINISH_MIGHT_TURNS = 10;
    static final int FINISH_COUNCIL_TURNS = 3;

    /**
     * Совет «почти набран», когда у кандидата больше половины ИЗВЕСТНЫХ голосов при нужных двух
     * третях всех: до избрания ему остаётся малая доля. Реконструкция по пункту 56 — голоса
     * незнакомых не видны, и точнее сказать нельзя, не нарушив туман.
     */
    static final int FINISH_COUNCIL_PERCENT = 50;

    /** Покорение близко, когда у империи четыре пятых колоний известной галактики. */
    static final int FINISH_CONQUEST_PERCENT = 80;

    private final GameAccess gameAccess;
    private final PlayerRepository playerRepository;
    private final PlanetRepository planetRepository;
    private final DiplomacyRelationRepository relationRepository;
    private final EmpireHistoryRepository historyRepository;
    private final EmpireMightRules empireMightRules;
    private final CouncilRules councilRules;
    private final Messages messages;

    public VictoryProgressService(GameAccess gameAccess, PlayerRepository playerRepository,
                                  PlanetRepository planetRepository, DiplomacyRelationRepository relationRepository,
                                  EmpireHistoryRepository historyRepository, EmpireMightRules empireMightRules,
                                  CouncilRules councilRules, Messages messages) {
        this.gameAccess = gameAccess;
        this.playerRepository = playerRepository;
        this.planetRepository = planetRepository;
        this.relationRepository = relationRepository;
        this.historyRepository = historyRepository;
        this.empireMightRules = empireMightRules;
        this.councilRules = councilRules;
        this.messages = messages;
    }

    @Transactional(readOnly = true)
    public VictoryProgressDto progress(UUID gameId, String accessToken) {
        UUID me = gameAccess.requireToken(gameId, accessToken);
        GameEntity game = gameAccess.requireGame(gameId);
        List<PlayerEntity> empires = playerRepository.findEmpiresByGameIdOrderBySlotAsc(gameId);
        Map<UUID, PlayerEntity> byId = empires.stream().collect(Collectors.toMap(PlayerEntity::getId, p -> p));
        Set<UUID> known = relationRepository.findAllByPlayerId(me).stream()
                .filter(relation -> Boolean.TRUE.equals(relation.getInContact()))
                .map(relation -> relation.getOtherPlayerId())
                .filter(byId::containsKey)
                .collect(Collectors.toSet());
        int unknown = (int) empires.stream().filter(p -> !p.getId().equals(me) && !known.contains(p.getId())).count();

        Map<UUID, Integer> population = new HashMap<>();
        Map<UUID, Integer> colonies = new HashMap<>();
        for (PlanetEntity colony : planetRepository.findAllByOwnerPlayerIdIn(byId.keySet())) {
            if (colony.getPopulation() != null && colony.getPopulation() > 0) {
                population.merge(colony.getOwnerPlayerId(), colony.getPopulation(), Integer::sum);
                colonies.merge(colony.getOwnerPlayerId(), 1, Integer::sum);
            }
        }
        int alive = colonies.size();
        int turn = game.getTurn();

        VictoryProgressDto.Council council = null;
        if (Boolean.TRUE.equals(game.getCouncil())) {
            int yours = population.getOrDefault(me, 0);
            int knownVotes = yours + known.stream().mapToInt(id -> population.getOrDefault(id, 0)).sum();
            Integer next = null;
            for (int t = turn; t < turn + CouncilRules.PERIOD + CouncilRules.FIRST_TURN; t++) {
                if (Boolean.TRUE.equals(councilRules.convenes(t, alive))) {
                    next = t;
                    break;
                }
            }
            council = new VictoryProgressDto.Council(yours, knownVotes,
                    100 * CouncilRules.MAJORITY_NUMERATOR / CouncilRules.MAJORITY_DENOMINATOR + 1, next, unknown);
        }
        VictoryProgressDto.Conquest conquest = new VictoryProgressDto.Conquest(colonies.getOrDefault(me, 0),
                (int) known.stream().filter(colonies::containsKey).count(), unknown);
        VictoryProgressDto.Thessar thessar = Boolean.TRUE.equals(game.getWardenholdVictory())
                ? new VictoryProgressDto.Thessar(WardenholdRules.turnsLeft(game) == null ? null
                        : game.getWardenholdHolderPlayerId(), WardenholdRules.turnsLeft(game), WardenholdRules.HOLD_TURNS)
                : null;

        Map<UUID, Integer> might = new HashMap<>();
        for (EmpireHistoryEntity row : historyRepository.findAllByGameIdAndTurn(gameId, turn - 1)) {
            if (row.getPlayerId().equals(me) || known.contains(row.getPlayerId())) {
                might.put(row.getPlayerId(), empireMightRules.might(row.getFleetPower(), row.getPopulationK(),
                        row.getProduction(), row.getResearch(), row.getTechnologies()));
            }
        }
        Integer mine = might.get(me);
        Integer place = mine == null ? null
                : 1 + (int) might.entrySet().stream().filter(e -> !e.getKey().equals(me) && e.getValue() > mine).count();
        VictoryProgressDto.Might mightDto = Boolean.TRUE.equals(game.getMightVictory())
                ? new VictoryProgressDto.Might(place, might.size(), Math.max(0, MightVictoryRules.limit(game) - turn),
                        MightVictoryRules.limit(game), game.getMightPendingPlayerId(),
                        game.getMightPendingPlayerId() != null && byId.containsKey(me)
                                && MightConsentService.asked(byId.get(me), game.getMightPendingPlayerId()),
                        byId.containsKey(me) ? byId.get(me).getMightAnswer() : null)
                : null;

        return new VictoryProgressDto(council, conquest, thessar, mightDto,
                milestone(me, byId, council, thessar, mightDto, might, turn),
                finish(me, byId, known, unknown, population, colonies, council, thessar, mightDto, might, turn));
    }

    /**
     * Финиш близко — field-backlog, пункт 56: самый напряжённый миг партии не должен проходить
     * строкой на панели. По каждому пути — своя ли победа близко («ещё N ходов») или чужая («что можно
     * сделать»), теми же правилами, что решают победу, и с тем же туманом, что у вехи: чужое — только
     * знакомых империй. Строки идут первым разделом итогов хода.
     */
    private List<VictoryProgressDto.Finish> finish(UUID me, Map<UUID, PlayerEntity> players, Set<UUID> known,
                                                   int unknown, Map<UUID, Integer> population,
                                                   Map<UUID, Integer> colonies, VictoryProgressDto.Council council,
                                                   VictoryProgressDto.Thessar thessar, VictoryProgressDto.Might might,
                                                   Map<UUID, Integer> mightBy, int turn) {
        List<VictoryProgressDto.Finish> lines = new java.util.ArrayList<>();
        if (thessar != null && thessar.holderPlayerId() != null && thessar.turnsLeft() != null
                && thessar.turnsLeft() <= FINISH_THESSAR_TURNS) {
            boolean yours = me.equals(thessar.holderPlayerId());
            lines.add(new VictoryProgressDto.Finish("THESSAR", yours, yours
                    ? messages.event("finish.thessar.yours", List.of(String.valueOf(thessar.turnsLeft())))
                    : messages.event("finish.thessar.rival", List.of(players.get(thessar.holderPlayerId()).getName(),
                            String.valueOf(thessar.turnsLeft())))));
        }
        if (council != null && council.nextTurn() != null && council.nextTurn() - turn <= FINISH_COUNCIL_TURNS
                && council.knownVotes() > 0) {
            UUID leader = null;
            int best = 0;
            for (UUID id : players.keySet()) {
                if (!id.equals(me) && !known.contains(id)) {
                    continue;
                }
                int votes = population.getOrDefault(id, 0);
                if (votes > best) {
                    best = votes;
                    leader = id;
                }
            }
            if (leader != null && 100 * best / council.knownVotes() > FINISH_COUNCIL_PERCENT) {
                int share = 100 * best / council.knownVotes();
                String left = String.valueOf(Math.max(0, council.nextTurn() - turn));
                lines.add(new VictoryProgressDto.Finish("COUNCIL", leader.equals(me), leader.equals(me)
                        ? messages.event("finish.council.yours", List.of(left, String.valueOf(share),
                                String.valueOf(council.neededPercent())))
                        : messages.event("finish.council.rival", List.of(players.get(leader).getName(), left,
                                String.valueOf(share), String.valueOf(council.neededPercent())))));
            }
        }
        if (might != null && might.turnsLeft() <= FINISH_MIGHT_TURNS && !mightBy.isEmpty()) {
            Map.Entry<UUID, Integer> strongest = mightBy.entrySet().stream()
                    .max(Map.Entry.<UUID, Integer>comparingByValue()
                            .thenComparing(entry -> players.get(entry.getKey()).getSlot(), java.util.Comparator.reverseOrder()))
                    .orElseThrow();
            boolean yours = strongest.getKey().equals(me);
            lines.add(new VictoryProgressDto.Finish("MIGHT", yours, yours
                    ? messages.event("finish.might.yours", List.of(String.valueOf(might.turnsLeft())))
                    : messages.event("finish.might.rival", List.of(players.get(strongest.getKey()).getName(),
                            String.valueOf(might.turnsLeft())))));
        }
        int knownColonies = colonies.getOrDefault(me, 0)
                + known.stream().mapToInt(id -> colonies.getOrDefault(id, 0)).sum();
        if (unknown == 0 && knownColonies > 0) {
            for (UUID id : players.keySet()) {
                if ((id.equals(me) || known.contains(id)) && colonies.getOrDefault(id, 0) * 100
                        >= FINISH_CONQUEST_PERCENT * knownColonies && colonies.size() > 1) {
                    boolean yours = id.equals(me);
                    int rivals = colonies.size() - 1;
                    lines.add(new VictoryProgressDto.Finish("CONQUEST", yours, yours
                            ? messages.event("finish.conquest.yours", List.of(String.valueOf(rivals)))
                            : messages.event("finish.conquest.rival", List.of(players.get(id).getName()))));
                }
            }
        }
        return lines;
    }

    /**
     * Ближайшая значимая веха — пункт 11: одна строка, по порядку важности. Звезду держат —
     * сколько ещё; совет вот-вот — какая у вас доля; итог по мощи близко — ваше место;
     * сильнейший знакомый вдвое сильнее — кто. Ничего из этого — строки нет.
     */
    private String milestone(UUID me, Map<UUID, PlayerEntity> players, VictoryProgressDto.Council council,
                             VictoryProgressDto.Thessar thessar, VictoryProgressDto.Might might,
                             Map<UUID, Integer> mightBy, int turn) {
        if (thessar != null && thessar.holderPlayerId() != null) {
            return me.equals(thessar.holderPlayerId())
                    ? messages.event("milestone.thessar.yours", List.of(String.valueOf(thessar.turnsLeft())))
                    : messages.event("milestone.thessar.rival", List.of(players.get(thessar.holderPlayerId()).getName(),
                            String.valueOf(thessar.turnsLeft())));
        }
        if (council != null && council.nextTurn() != null && council.nextTurn() - turn <= COUNCIL_SOON_TURNS
                && council.knownVotes() > 0) {
            int share = 100 * council.yourVotes() / council.knownVotes();
            return messages.event("milestone.council", List.of(String.valueOf(council.nextTurn() - turn),
                    String.valueOf(share), String.valueOf(council.neededPercent())));
        }
        if (might != null && might.place() != null && might.turnsLeft() <= MIGHT_SOON_TURNS) {
            return messages.event("milestone.might", List.of(String.valueOf(might.turnsLeft()),
                    String.valueOf(might.place()), String.valueOf(might.ranked())));
        }
        Integer mine = mightBy.get(me);
        if (mine != null) {
            Map.Entry<UUID, Integer> strongest = mightBy.entrySet().stream()
                    .filter(e -> !e.getKey().equals(me))
                    .max(Map.Entry.comparingByValue())
                    .orElse(null);
            if (strongest != null && strongest.getValue() >= RIVAL_RATIO * Math.max(1, mine)) {
                return messages.event("milestone.rival", List.of(players.get(strongest.getKey()).getName(),
                        String.valueOf(strongest.getValue() / Math.max(1, mine))));
            }
        }
        return null;
    }
}
