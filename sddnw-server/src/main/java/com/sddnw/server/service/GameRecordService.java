package com.sddnw.server.service;

import com.sddnw.server.domain.entity.EmpireHistoryEntity;
import com.sddnw.server.domain.entity.GameEntity;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.entity.history.GameRecordEntity;
import com.sddnw.server.dto.GameHistoryDto;
import com.sddnw.server.repository.EmpireHistoryRepository;
import com.sddnw.server.repository.history.GameRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * История своих партий — field-backlog, пункт 18: видимый рост умения между партиями.
 * <p>
 * Пишется в конце партии ({@code VictoryService.finish}) каждому человеку с учётной записью и
 * при признанном поражении ({@code ConcessionService}) — тому, кто признал. Источник данных —
 * ИСТОРИИ, своей транзакцией ({@code REQUIRES_NEW}): партию потом удаляют, а строка остаётся.
 * Числа — из летописи империй, той же, что график «Инфо»: выборка одна и случается раз на партию.
 * Личные рекорды считаются при чтении из самих строк — второй правды о них нет.
 */
@Service
public class GameRecordService {

    public static final String WON = "WON";
    public static final String LOST = "LOST";
    public static final String CONCEDED = "CONCEDED";

    private final GameRecordRepository records;
    private final EmpireHistoryRepository history;
    private final EmpireMightRules mightRules;
    private final com.sddnw.server.repository.ShipDesignRepository designs;
    private final com.sddnw.server.repository.EmpireActivityRepository activity;
    private final com.sddnw.server.repository.GameMilestoneRepository milestones;
    private final RaceTemplateService raceTemplates;
    private final RaceTraitCatalog raceCatalog;

    /** Имена в списке рейтинга (пункт 19); необязательна — тестам без неё список не нужен. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.sddnw.server.repository.history.AccountRepository accounts;

    /** Подарки за приглашение (пункты 50, 174) в истории партий; необязательны. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private CompanionService companions;

    /** Знак раннего доступа в списке рейтинга (пункт 147); необязателен. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private EarlyBirdService earlyBirds;

    public GameRecordService(GameRecordRepository records, EmpireHistoryRepository history,
                             EmpireMightRules mightRules, com.sddnw.server.repository.ShipDesignRepository designs,
                             com.sddnw.server.repository.EmpireActivityRepository activity,
                             com.sddnw.server.repository.GameMilestoneRepository milestones,
                             RaceTemplateService raceTemplates, RaceTraitCatalog raceCatalog) {
        this.records = records;
        this.history = history;
        this.mightRules = mightRules;
        this.designs = designs;
        this.activity = activity;
        this.milestones = milestones;
        this.raceTemplates = raceTemplates;
        this.raceCatalog = raceCatalog;
    }

    /** Звания, которые принесла эта партия (пункт 41): сцена конца называет их. */
    @Transactional(value = "historyTransactionManager", readOnly = true)
    public List<String> titlesEarnedBy(UUID accountId, UUID gameId) {
        if (accountId == null) {
            return List.of();
        }
        return TitleRules.earnedBy(records.findAllByAccountIdOrderByFinishedAtDesc(accountId), gameId,
                raceCatalog.readyRaces().size());
    }

    /**
     * Свои проекты игрока в партии — field-backlog, пункт 98: собранные ИГРОКОМ, а не игрой
     * (признак {@code auto}, ячейки выше нуля — ноль и ниже собирает игра), не трофеи, и по
     * которым строили. Проект правится новой строкой (построенное остаётся с тем, с чем
     * построено), поэтому версии одного проекта сводятся по имени. Лучший — больше всего
     * построено — первым; ничья — по имени, чтобы ответ не прыгал.
     */
    public static LinkedHashMap<String, Integer> craftedDesigns(List<com.sddnw.server.domain.entity.ShipDesignEntity> all,
                                                                UUID playerId) {
        Map<String, Integer> built = new HashMap<>();
        for (var design : all) {
            if (playerId.equals(design.getOwnerPlayerId()) && !Boolean.TRUE.equals(design.getAuto())
                    && !Boolean.TRUE.equals(design.getTrophy()) && design.getSlot() != null && design.getSlot() > 0
                    && design.getBuilt() != null && design.getBuilt() > 0) {
                built.merge(design.getName(), design.getBuilt(), Integer::sum);
            }
        }
        LinkedHashMap<String, Integer> sorted = new LinkedHashMap<>();
        built.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return sorted;
    }

    /**
     * Записать партию тем, кто её прочтёт: людям с учётной записью. Дважды одну партию одной
     * записи не пишем: признавший поражение уже записан, а партия идёт до конца без него.
     */
    @Transactional(value = "historyTransactionManager", propagation = Propagation.REQUIRES_NEW)
    public void record(GameEntity game, List<PlayerEntity> empires, List<PlayerEntity> readers,
                       UUID winnerId, String victoryKind, Integer turn, String forcedOutcome) {
        List<PlayerEntity> wanted = readers.stream().filter(reader -> reader.getAccountId() != null).toList();
        // Партия из файла (пункт 160) в историю не идёт: файл мог быть поправлен руками.
        // Партия на своих справочниках (пункт 42) в историю и сравнения не идёт, как и поднятая из файла.
        if (wanted.isEmpty() || Boolean.TRUE.equals(game.getImported()) || Boolean.TRUE.equals(game.getModded())) {
            return;
        }
        Map<UUID, Map<Integer, Integer>> might = new HashMap<>();
        Map<UUID, Integer> peakColonies = new HashMap<>();
        for (EmpireHistoryEntity row : history.findAllByGameIdOrderByTurnAsc(game.getId())) {
            might.computeIfAbsent(row.getPlayerId(), id -> new LinkedHashMap<>()).put(row.getTurn(),
                    mightRules.might(row.getFleetPower(), row.getPopulationK(), row.getProduction(),
                            row.getResearch(), row.getTechnologies()));
            peakColonies.merge(row.getPlayerId(), row.getColonies(), Math::max);
        }
        Integer lastTurn = might.values().stream().flatMap(byTurn -> byTurn.keySet().stream())
                .max(Integer::compare).orElse(turn);
        List<com.sddnw.server.domain.entity.ShipDesignEntity> gameDesigns = designs.findAllByGameId(game.getId());
        // Чем партия была трудна (пункт 41): войны — счётчиком объявивших и главным моментом тех,
        // кому объявили; Тессар и абордаж — главными моментами летописи.
        java.util.Set<UUID> declaredWar = new java.util.HashSet<>();
        for (var counter : activity.findAllByGameIdOrderByPlayerIdAscCodeAsc(game.getId())) {
            if (EmpireActivityService.WAR.equals(counter.getCode()) && counter.getTimes() != null && counter.getTimes() > 0) {
                declaredWar.add(counter.getPlayerId());
            }
        }
        Map<UUID, java.util.Set<String>> moments = new HashMap<>();
        for (var moment : milestones.findAllByGameIdOrderByTurnAscPlayerIdAscKindAsc(game.getId())) {
            moments.computeIfAbsent(moment.getPlayerId(), id -> new java.util.HashSet<>()).add(moment.getKind());
        }
        for (PlayerEntity reader : wanted) {
            if (Boolean.TRUE.equals(records.existsByAccountIdAndGameId(reader.getAccountId(), game.getId()))) {
                continue;
            }
            int mine = might.getOrDefault(reader.getId(), Map.of()).getOrDefault(lastTurn, 0);
            int place = 1;
            for (PlayerEntity other : empires) {
                if (!other.getId().equals(reader.getId())
                        && might.getOrDefault(other.getId(), Map.of()).getOrDefault(lastTurn, 0) > mine) {
                    place++;
                }
            }
            String outcome = forcedOutcome != null ? forcedOutcome
                    // Союзники, разделившие покорение (пункт 145), победили тоже.
                    : reader.getId().equals(winnerId) || game.winners().contains(reader.getId()) ? WON : LOST;
            GameRecordEntity row = new GameRecordEntity();
            row.setAccountId(reader.getAccountId());
            row.setGameId(game.getId());
            row.setGameName(game.getName());
            row.setRaceName(reader.getRaceName());
            row.setGalaxySize(game.getGalaxySize() == null ? null : game.getGalaxySize().name());
            row.setEmpires(empires.size());
            row.setOutcome(outcome);
            row.setVictoryKind(victoryKind);
            row.setTurn(turn);
            row.setPlace(place);
            row.setPeakColonies(peakColonies.getOrDefault(reader.getId(), 0));
            java.util.Set<String> own = moments.getOrDefault(reader.getId(), java.util.Set.of());
            boolean readyRace = reader.getRaceName() == null || reader.getRaceName().isBlank();
            row.setRaceCode(readyRace ? reader.getRaceCode() : null);
            row.setAtWar(declaredWar.contains(reader.getId()) || own.contains(MilestoneService.WAR));
            row.setWarden(own.contains(MilestoneService.GUARDIANS));
            row.setTrophy(own.contains(MilestoneService.TROPHY));
            // Партия не всерьёз — учебная или по гостевым правилам (мягче настоящих): в звания не идёт.
            row.setTutorial(Boolean.TRUE.equals(game.getTutorial()) || Boolean.TRUE.equals(game.getRichStart()));
            row.setWeeklyWeek(game.getWeeklyWeek());
            row.setAiSkill(game.getAiSkill());
            // Хозяин стола (пункт 67): завёл партию, и за столом сидели другие люди.
            row.setHosted(reader.getId().equals(game.getHostPlayerId()) && humansAtTable(empires) >= 2);
            // Кто позвал (пункты 50, 174): код приглашения у партии один, позвал — хозяин стола.
            if (Boolean.TRUE.equals(reader.getInvited())) {
                empires.stream().filter(other -> other.getId().equals(game.getHostPlayerId()))
                        .map(PlayerEntity::getAccountId)
                        .filter(host -> host != null && !host.equals(reader.getAccountId()))
                        .findFirst().ifPresent(row::setInvitedBy);
            }
            if (WON.equals(outcome)) {
                HardshipRules.Deficit deficit = HardshipRules.deepest(might, reader.getId());
                if (deficit != null) {
                    row.setDeficitTurn(deficit.turn());
                    row.setDeficitPercent(deficit.percent());
                }
            }
            // Мощь на контрольных ходах (пункт 115): с ней следующая партия сравнит игрока с собой.
            Map<Integer, Integer> ownMight = might.getOrDefault(reader.getId(), Map.of());
            for (Integer checkpoint : CheckpointRules.TURNS) {
                row.setMightAt(checkpoint, CheckpointRules.at(ownMight, checkpoint));
            }
            row.setMightEnd(mine);
            // Свои проекты (пункт 98): какими собранными кораблями играли — лучший первым.
            String crafted = craftedDesigns(gameDesigns, reader.getId()).entrySet().stream().limit(5)
                    .map(entry -> entry.getKey().replace("|", "/").replace(":", " ") + ":" + entry.getValue())
                    .collect(java.util.stream.Collectors.joining("|"));
            row.setDesigns(crafted.isEmpty() ? null : crafted);
            row.setFinishedAt(OffsetDateTime.now());
            records.save(row);
            // Партия чужой расой (пункт 99): автор узнает, чем она кончилась.
            raceTemplates.finished(reader.getAccountId(), game.getId(), outcome);
        }
    }

    /**
     * Сравнение с собой прошлым — field-backlog, пункт 115: последний пройденный контрольный ход
     * этой партии против ЛУЧШЕЙ своей партии той же настройки (размер галактики и число империй
     * — иначе это не сравнение) на том же ходу. Пусто — сравнивать не с чем.
     */
    @Transactional(value = "historyTransactionManager", readOnly = true)
    public com.sddnw.server.dto.PastSelfDto compare(UUID accountId, UUID gameId, String galaxySize, Integer empires,
                                                    Map<Integer, Integer> myMightByTurn) {
        if (accountId == null) {
            return null;
        }
        List<GameRecordEntity> similar = records.findAllByAccountIdOrderByFinishedAtDesc(accountId).stream()
                .filter(row -> !row.getGameId().equals(gameId))
                .filter(row -> java.util.Objects.equals(row.getGalaxySize(), galaxySize)
                        && java.util.Objects.equals(row.getEmpires(), empires))
                .toList();
        for (int i = CheckpointRules.TURNS.size() - 1; i >= 0; i--) {
            int checkpoint = CheckpointRules.TURNS.get(i);
            Integer mine = CheckpointRules.at(myMightByTurn, checkpoint);
            if (mine == null) {
                continue;
            }
            Integer best = similar.stream().map(row -> row.mightAt(checkpoint))
                    .filter(java.util.Objects::nonNull).max(Integer::compare).orElse(null);
            if (best == null || best <= 0) {
                return null;
            }
            int percent = (int) Math.round(100.0 * (mine - best) / best);
            return new com.sddnw.server.dto.PastSelfDto(checkpoint, mine, best, percent);
        }
        return null;
    }

    /** Людей за столом — с учётной записью; признавших поражение тоже: за столом они сидели. */
    private static long humansAtTable(List<PlayerEntity> empires) {
        return empires.stream().filter(player -> player.getAccountId() != null).count();
    }

    /** Свои проекты из строки истории: «имя:построено» через «|». */
    private static List<com.sddnw.server.dto.OwnDesignDto> ownDesigns(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        List<com.sddnw.server.dto.OwnDesignDto> result = new ArrayList<>();
        for (String part : stored.split("\\|")) {
            int colon = part.lastIndexOf(':');
            if (colon > 0) {
                try {
                    result.add(new com.sddnw.server.dto.OwnDesignDto(part.substring(0, colon),
                            Integer.valueOf(part.substring(colon + 1))));
                } catch (NumberFormatException broken) {
                    // Испорченная строка истории не роняет экран — просто пропускается.
                }
            }
        }
        return result;
    }

    /**
     * Доля среди похожих для этой партии — field-backlog, пункты 117 и 118. Пусто — сравнивать
     * рано (своих партий мало) или не с чем (похожих чужих мало), см. {@link PeerRules}.
     */
    @Transactional(value = "historyTransactionManager", readOnly = true)
    public com.sddnw.server.dto.PeerShareDto peersOf(UUID accountId, UUID gameId) {
        if (accountId == null) {
            return null;
        }
        List<GameRecordEntity> own = records.findAllByAccountIdOrderByFinishedAtDesc(accountId);
        GameRecordEntity mine = own.stream().filter(row -> row.getGameId().equals(gameId)).findFirst().orElse(null);
        if (mine == null) {
            return null;
        }
        PeerRules.Share share = PeerRules.share(mine, own.size(),
                living(records.findAllByGalaxySizeAndEmpires(mine.getGalaxySize(), mine.getEmpires())));
        return share == null ? null : new com.sddnw.server.dto.PeerShareDto(share.games(), share.coloniesAbove(),
                share.fasterThan(), !PeerRules.serious(mine));
    }

    /**
     * Рейтинг записи — field-backlog, пункт 19: с этой партией ({@code gameId} — сдвиг от неё) или
     * со списком всех (экран истории). Пусто — своих партий меньше трёх (пункт 118) или партий с
     * людьми не было вовсе: рейтинг из одних партий против ИИ ничего не значит.
     */
    @Transactional(value = "historyTransactionManager", readOnly = true)
    public com.sddnw.server.dto.RatingDto ratingOf(UUID accountId, UUID gameId) {
        if (accountId == null) {
            return null;
        }
        List<GameRecordEntity> all = living(records.findAll());
        long own = all.stream().filter(row -> row.getAccountId().equals(accountId)).count();
        Map<UUID, PeerRules.Rating> ratings = PeerRules.ratings(all);
        PeerRules.Rating mine = ratings.get(accountId);
        if (own < PeerRules.MIN_OWN_GAMES || mine == null) {
            return null;
        }
        if (gameId != null) {
            Integer delta = mine.deltaByGame().get(gameId);
            return delta == null ? null
                    : new com.sddnw.server.dto.RatingDto(mine.value(), mine.games(), mine.provisional(), delta, null);
        }
        List<com.sddnw.server.dto.RatingDto.Line> board = new ArrayList<>();
        if (accounts != null) {
            Map<UUID, String> names = new HashMap<>();
            java.util.Set<UUID> early = earlyBirds == null ? java.util.Set.of() : earlyBirds.among(ratings.keySet());
            accounts.findAllById(ratings.keySet()).forEach(account -> names.put(account.getId(), account.getName()));
            ratings.entrySet().stream()
                    .sorted(Comparator.<Map.Entry<UUID, PeerRules.Rating>>comparingInt(entry -> -entry.getValue().value())
                            .thenComparing(entry -> names.getOrDefault(entry.getKey(), "")))
                    .limit(BOARD_SIZE)
                    .forEach(entry -> board.add(new com.sddnw.server.dto.RatingDto.Line(
                            names.getOrDefault(entry.getKey(), "?"), entry.getValue().value(), entry.getValue().games(),
                            entry.getKey().equals(accountId), early.contains(entry.getKey()))));
        }
        return new com.sddnw.server.dto.RatingDto(mine.value(), mine.games(), mine.provisional(), null, board);
    }

    /**
     * Строки живых записей: история удалённой уходит вместе с ней, но строки, оставшиеся от записей,
     * удалённых раньше (в основном временные записи сквозного прогона), в сравнения не идут.
     */
    private List<GameRecordEntity> living(List<GameRecordEntity> rows) {
        if (accounts == null || rows.isEmpty()) {
            return rows;
        }
        java.util.Set<UUID> alive = new java.util.HashSet<>();
        accounts.findAllById(rows.stream().map(GameRecordEntity::getAccountId).collect(java.util.stream.Collectors.toSet()))
                .forEach(account -> alive.add(account.getId()));
        return rows.stream().filter(row -> alive.contains(row.getAccountId())).toList();
    }

    /** Сколько строк в списке рейтинга: людей пока мало, и длиннее он стал бы таблицей (пункт 19). */
    public static final int BOARD_SIZE = 20;

    /** Профили записей для подбора стола (пункт 119): опыт и рейтинг. */
    @Transactional(value = "historyTransactionManager", readOnly = true)
    public Map<UUID, PeerRules.Profile> profiles(java.util.Collection<UUID> accountIds) {
        Map<UUID, PeerRules.Rating> ratings = PeerRules.ratings(living(records.findAll()));
        Map<UUID, List<GameRecordEntity>> byAccount = new HashMap<>();
        for (GameRecordEntity row : records.findAllByAccountIdIn(accountIds)) {
            byAccount.computeIfAbsent(row.getAccountId(), id -> new ArrayList<>()).add(row);
        }
        Map<UUID, PeerRules.Profile> result = new HashMap<>();
        for (UUID accountId : accountIds) {
            result.put(accountId, PeerRules.profile(byAccount.getOrDefault(accountId, List.of()), ratings.get(accountId)));
        }
        return result;
    }

    /** История записи с личными рекордами — экран «История партий». */
    /** Подсказка уровня следующей партии по прошлой — field-backlog, пункт 10; {@code null} — нечего. */
    @Transactional(value = "historyTransactionManager", readOnly = true)
    public DifficultyRules.Suggestion difficultyFor(UUID accountId) {
        return DifficultyRules.suggest(records.findAllByAccountIdOrderByFinishedAtDesc(accountId));
    }

    @Transactional(value = "historyTransactionManager", readOnly = true)
    public GameHistoryDto historyOf(UUID accountId) {
        List<GameRecordEntity> rows = records.findAllByAccountIdOrderByFinishedAtDesc(accountId);
        List<GameHistoryDto.Game> games = new ArrayList<>();
        for (GameRecordEntity row : rows) {
            games.add(new GameHistoryDto.Game(row.getGameName(), row.getRaceName(), row.getGalaxySize(),
                    row.getEmpires(), row.getOutcome(), row.getVictoryKind(), row.getTurn(), row.getPlace(),
                    row.getPeakColonies(), row.getDeficitTurn(), row.getDeficitPercent(), row.getFinishedAt(),
                    ownDesigns(row.getDesigns())));
        }
        Integer fastestWin = rows.stream().filter(row -> WON.equals(row.getOutcome()))
                .map(GameRecordEntity::getTurn).min(Comparator.naturalOrder()).orElse(null);
        Integer mostColonies = rows.stream().map(GameRecordEntity::getPeakColonies)
                .max(Comparator.naturalOrder()).orElse(null);
        Integer hardestWin = rows.stream().filter(row -> row.getDeficitPercent() != null)
                .map(GameRecordEntity::getDeficitPercent).max(Comparator.naturalOrder()).orElse(null);
        long wins = rows.stream().filter(row -> WON.equals(row.getOutcome())).count();
        return new GameHistoryDto(games, rows.size(), (int) wins, fastestWin,
                mostColonies == null || mostColonies == 0 ? null : mostColonies, hardestWin,
                TitleRules.titles(rows, raceCatalog.readyRaces().size()), ratingOf(accountId, null),
                companions == null ? null : companions.count(List.of(accountId)).getOrDefault(accountId, 0));
    }
}
