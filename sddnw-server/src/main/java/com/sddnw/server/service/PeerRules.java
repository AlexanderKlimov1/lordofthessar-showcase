package com.sddnw.server.service;

import com.sddnw.server.domain.entity.history.GameRecordEntity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * С кем игрок меряет себя — field-backlog, пункты 19, 117, 118 и 119. Всё считается при чтении из
 * истории партий ({@code game_record}): второй правды — ни таблицы рейтинга, ни счётчиков — нет, и
 * рейтинг всегда тот, что выводится из сыгранного.
 * <p>
 * <b>Рейтинг (пункт 19)</b> — только партии, где за столом сидели хотя бы двое людей: в истории у
 * такой партии строки нескольких учётных записей. Победа над ИИ в рейтинг не идёт, иначе его
 * выигрывает тот, кто сыграл больше лёгких партий. Считается Эло для многих участников: партия
 * разбивается на пары людей, исход пары — кто выше по месту мощи в конце, а поправка каждого
 * делится на число его соперников-людей, чтобы партия на четверых не весила втрое больше дуэли.
 * Партии не всерьёз (учебные и по гостевым правилам — признак {@code tutorial}) в рейтинг не идут.
 * <p>
 * <b>Доля среди похожих (пункт 117)</b> — «из сыгравших малую галактику на четверых вы основали
 * колоний больше, чем 70 %», а не «вы 8341-й». Похожие — партии ДРУГИХ записей той же настройки
 * (размер галактики, число империй) и тех же правил: гостевые сравниваются только с гостевыми
 * (пункт 118). Хранить для этого ничего нового не нужно: строки истории уже обезличены для
 * сравнения — наружу уходит одна доля, без имён и чисел чужих партий.
 * <p>
 * <b>Новичка не ставить рядом с ветераном (пункт 118)</b>: пока у игрока меньше
 * {@link #MIN_OWN_GAMES} сыгранных партий, сравнения с людьми нет вовсе — ни доли, ни рейтинга, —
 * только с собой прошлым (пункт 115) и с соседями по партии (пункт 114).
 * <p>
 * <b>Соперники по силе (пункт 119)</b>: стол подбора выбирается по сходству с теми, кто за ним уже
 * сидит, — опыт и рейтинг, — а не первый свободный. Пустого лобби подбор не заводит ради сходства:
 * свободный стол есть — человек садится за самый похожий.
 */
public final class PeerRules {

    /** Сыгранных партий, после которых игрока сравнивают с людьми (пункт 118). */
    public static final int MIN_OWN_GAMES = 3;

    /**
     * Похожих чужих партий, с которых доля что-то значит (пункты 117 и 101): доля из двадцати
     * партий — шум, на тридцати ошибка доли около девяти пунктов, и это уже число, а не жребий.
     */
    public static final int MIN_SIMILAR_GAMES = 30;

    /** С чего начинает каждый в рейтинге. */
    public static final int START_RATING = 1500;

    /** Шаг Эло на одну пару; у партии на многих он делится между соперниками. */
    public static final int K = 32;

    /** Рейтинговых партий, пока рейтинг предварительный: на одной-двух он ещё ничего не говорит. */
    public static final int PROVISIONAL_GAMES = 5;

    private PeerRules() {
    }

    /** Рейтинг записи: значение, сколько партий в нём и сдвиг по каждой партии. */
    public record Rating(Integer value, Integer games, Map<UUID, Integer> deltaByGame) {

        public Boolean provisional() {
            return games < PROVISIONAL_GAMES;
        }
    }

    /** Партия всерьёз: не учебная и не по гостевым правилам. Строки до миграции 130 считаются всерьёз. */
    static boolean serious(GameRecordEntity row) {
        return !Boolean.TRUE.equals(row.getTutorial());
    }

    /**
     * Рейтинг всех, кто играл с людьми, в порядке сыгранного: партии идут по времени конца,
     * а внутри партии поправки считаются от рейтингов ДО неё — порядок пар ничего не меняет.
     */
    public static Map<UUID, Rating> ratings(List<GameRecordEntity> all) {
        Map<UUID, List<GameRecordEntity>> byGame = new LinkedHashMap<>();
        all.stream().filter(PeerRules::serious)
                .sorted(Comparator.comparing(GameRecordEntity::getFinishedAt)
                        .thenComparing(GameRecordEntity::getGameName))
                .forEach(row -> byGame.computeIfAbsent(row.getGameId(), id -> new ArrayList<>()).add(row));
        Map<UUID, Double> value = new HashMap<>();
        Map<UUID, Integer> games = new HashMap<>();
        Map<UUID, Map<UUID, Integer>> deltas = new HashMap<>();
        for (Map.Entry<UUID, List<GameRecordEntity>> game : byGame.entrySet()) {
            List<GameRecordEntity> humans = game.getValue();
            if (humans.size() < 2) {
                continue;
            }
            Map<UUID, Double> change = new HashMap<>();
            for (GameRecordEntity a : humans) {
                double ra = value.getOrDefault(a.getAccountId(), (double) START_RATING);
                double sum = 0;
                for (GameRecordEntity b : humans) {
                    if (a == b) {
                        continue;
                    }
                    double rb = value.getOrDefault(b.getAccountId(), (double) START_RATING);
                    double expected = 1.0 / (1.0 + Math.pow(10, (rb - ra) / 400.0));
                    sum += score(a, b) - expected;
                }
                change.put(a.getAccountId(), K * sum / (humans.size() - 1));
            }
            for (Map.Entry<UUID, Double> entry : change.entrySet()) {
                value.merge(entry.getKey(), (double) START_RATING + entry.getValue(),
                        (old, ignored) -> old + entry.getValue());
                games.merge(entry.getKey(), 1, Integer::sum);
                deltas.computeIfAbsent(entry.getKey(), id -> new HashMap<>())
                        .put(game.getKey(), (int) Math.round(entry.getValue()));
            }
        }
        Map<UUID, Rating> result = new HashMap<>();
        for (Map.Entry<UUID, Double> entry : value.entrySet()) {
            result.put(entry.getKey(), new Rating((int) Math.round(entry.getValue()), games.get(entry.getKey()),
                    deltas.get(entry.getKey())));
        }
        return result;
    }

    /**
     * Исход пары: выше по месту — выиграл, одно место — ничья. Победа в партии старше места: союзник,
     * разделивший покорение (пункт 145), не проигрывает тому, с кем победил, даже будучи слабее.
     */
    static double score(GameRecordEntity a, GameRecordEntity b) {
        boolean aWon = GameRecordService.WON.equals(a.getOutcome());
        boolean bWon = GameRecordService.WON.equals(b.getOutcome());
        if (aWon != bWon) {
            return aWon ? 1.0 : 0.0;
        }
        int compare = Integer.compare(b.getPlace(), a.getPlace());
        return compare > 0 ? 1.0 : compare < 0 ? 0.0 : 0.5;
    }

    /**
     * Доля среди похожих — пункт 117.
     *
     * @param games         сколько похожих партий
     * @param coloniesAbove у какой доли из них колоний меньше, чем у игрока, %
     * @param fasterThan    у какой доли похожих ПОБЕД победа пришла позже, %; пусто — не победа или побед мало
     */
    public record Share(Integer games, Integer coloniesAbove, Integer fasterThan) {
    }

    /**
     * Сравнить партию с похожими чужими — пустой ответ, если сравнивать рано (пункт 118: своих
     * партий меньше {@link #MIN_OWN_GAMES}) или не с чем (похожих меньше {@link #MIN_SIMILAR_GAMES}).
     */
    public static Share share(GameRecordEntity mine, int ownGames, List<GameRecordEntity> candidates) {
        if (ownGames < MIN_OWN_GAMES) {
            return null;
        }
        List<GameRecordEntity> similar = candidates.stream()
                .filter(row -> !row.getAccountId().equals(mine.getAccountId()))
                .filter(row -> Objects.equals(row.getGalaxySize(), mine.getGalaxySize())
                        && Objects.equals(row.getEmpires(), mine.getEmpires())
                        && serious(row) == serious(mine))
                .toList();
        if (similar.size() < MIN_SIMILAR_GAMES) {
            return null;
        }
        long fewer = similar.stream().filter(row -> row.getPeakColonies() < mine.getPeakColonies()).count();
        Integer faster = null;
        if (GameRecordService.WON.equals(mine.getOutcome())) {
            List<GameRecordEntity> wins = similar.stream()
                    .filter(row -> GameRecordService.WON.equals(row.getOutcome())).toList();
            if (wins.size() >= MIN_SIMILAR_GAMES) {
                long later = wins.stream().filter(row -> row.getTurn() > mine.getTurn()).count();
                faster = percent(later, wins.size());
            }
        }
        return new Share(similar.size(), percent(fewer, similar.size()), faster);
    }

    private static int percent(long part, int whole) {
        return (int) Math.round(100.0 * part / whole);
    }

    /** Чем игрок похож на других за столом подбора (пункт 119): опыт и рейтинг. */
    public record Profile(Integer games, Integer rating) {
    }

    /** Профиль записи по её истории и рейтингу; у новичка — ноль партий и стартовый рейтинг. */
    public static Profile profile(List<GameRecordEntity> own, Rating rating) {
        int games = (int) own.stream().filter(PeerRules::serious).count();
        return new Profile(games, rating == null ? START_RATING : rating.value());
    }

    /**
     * Насколько двое непохожи (пункт 119): опыт меряется логарифмом — разница между второй и
     * пятой партией больше, чем между сороковой и шестидесятой, — а рейтинг в долях шага Эло
     * (четыреста очков — разница, при которой сильный выигрывает девять пар из десяти).
     */
    public static double distance(Profile a, Profile b) {
        return Math.abs(Math.log1p(a.games()) - Math.log1p(b.games()))
                + Math.abs(a.rating() - b.rating()) / 400.0;
    }

    /** Непохожесть пришедшего на стол: среднее по людям, которые за ним уже сидят. */
    public static double distanceToTable(Profile mine, List<Profile> seated) {
        return seated.stream().mapToDouble(other -> distance(mine, other)).average().orElse(0);
    }
}
