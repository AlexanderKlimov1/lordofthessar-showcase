package com.sddnw.server.service;

import com.sddnw.server.domain.enums.PlanetSize;
import com.sddnw.server.dto.RulesBookDto;
import com.sddnw.server.service.stub.TurnPhase;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Справочник «Как устроено» — field-backlog, пункты 75 и 200.
 * <p>
 * Принять правила можно, только если их можно узнать, а без верной картины порядка хода игрок
 * ошибается «логично»: не понимает, почему корабль достроен и уже в бою. Поэтому здесь две вещи.
 * <ul>
 *   <li><b>Порядок конца хода</b> берётся из САМИХ фаз — бинов {@link TurnPhase} и их номеров,
 *   а не списком в тексте: добавленная фаза попадает в схему сама, а её слова держит проверка
 *   ({@code RulesBookKeysTest} — у каждой фазы есть название и пояснение в обоих словарях).</li>
 *   <li><b>Статьи о правилах</b> подставляют числа из классов правил ({@link CouncilRules},
 *   {@link BattleRules}, {@link PopulationCalculator}, {@link CommandRules}…), а не держат их
 *   переписанными в текст: иначе справочник разошёлся бы с игрой на первой же правке баланса.
 *   Пример загрязнения считает сама формула.</li>
 * </ul>
 * Где правило этой игры отличается от оригинала, у статьи есть строка отступления — клиент
 * показывает её знаком ◇, как {@code RuleNote}.
 */
@Service
public class RulesBookService {

    /** Пример загрязнения — тот же, что у оригинала (javadoc {@link PopulationCalculator#pollution}). */
    private static final int EXAMPLE_PRODUCTION = 100;
    private static final int EXAMPLE_CLEAN = 15;
    private static final int EXAMPLE_DIVISOR = 2;

    private final List<TurnPhase> phases;
    private final Messages messages;
    private final PopulationCalculator calculator;

    public RulesBookService(List<TurnPhase> phases, Messages messages, PopulationCalculator calculator) {
        this.phases = phases;
        this.messages = messages;
        this.calculator = calculator;
    }

    /** Ключ слов фазы — по имени её класса: имя фазы в журнале русское и для словаря не годится. */
    public static String phaseKey(Class<?> phase) {
        return "rules.phase." + phase.getSimpleName();
    }

    public RulesBookDto book() {
        List<RulesBookDto.Phase> order = phases.stream()
                .sorted(Comparator.comparing(TurnPhase::order))
                .map(phase -> {
                    String key = phaseKey(phase.getClass());
                    return new RulesBookDto.Phase(phase.getClass().getSimpleName(), phase.order(),
                            messages.get(key + ".title"), messages.get(key + ".text"));
                })
                .toList();
        return new RulesBookDto(order, articles());
    }

    private List<RulesBookDto.Article> articles() {
        int majority = (100 * CouncilRules.MAJORITY_NUMERATOR + CouncilRules.MAJORITY_DENOMINATOR - 1)
                / CouncilRules.MAJORITY_DENOMINATOR;
        int tolerance = PlanetSize.MEDIUM.getPollutionTolerance();
        int cleanup = calculator.pollution(EXAMPLE_PRODUCTION, EXAMPLE_CLEAN, tolerance, EXAMPLE_DIVISOR);
        List<RulesBookDto.Article> list = new ArrayList<>();
        list.add(article("victory", true, majority, WardenholdRules.HOLD_TURNS, MightVictoryRules.TURN_LIMIT));
        list.add(article("council", true, CouncilRules.FIRST_TURN, CouncilRules.PERIOD,
                CouncilRules.MIN_EMPIRES, majority, CouncilRules.MIN_TRUST_TO_VOTE));
        list.add(article("growth", false, PopulationCalculator.FOOD_PER_COLONIST,
                PopulationCalculator.RESEARCH_POINTS_PER_SCIENTIST, PopulationCalculator.TAX_PER_COLONIST));
        list.add(article("pollution", false, EXAMPLE_PRODUCTION, EXAMPLE_CLEAN, tolerance, EXAMPLE_DIVISOR,
                cleanup, EXAMPLE_PRODUCTION - cleanup));
        list.add(article("buying", true, PopulationCalculator.BUY_RATE_FRESH,
                PopulationCalculator.BUY_RATE_HALF_BUILT, PopulationCalculator.SELL_DIVISOR));
        list.add(article("battle", false, BattleRules.FIELD_WIDTH, BattleRules.FIELD_HEIGHT,
                BattleRules.HIT_RATING_STEP, BattleRules.DISSIPATION_PERCENT_PER_STEP,
                BattleRules.DISSIPATION_STEP_CELLS, BattleRules.MIN_DAMAGE_PERCENT,
                BattleRules.SYSTEM_HIT_PERCENT, BattleRules.ENGINE_BLAST_PERCENT));
        list.add(article("ground", false, GroundCombatService.STRENGTH_PER_COLONIST));
        list.add(article("trust", false, DiplomacyService.INITIAL_TRUST, DiplomacyService.AGREEMENT_BONUS,
                DiplomacyService.BREAK_PENALTY, DiplomacyService.WAR_PENALTY, DiplomacyService.PEACE_TRUST));
        list.add(article("command", false, CommandRules.POINTS_PER_COLONY, CommandRules.COST_PER_EXCESS_POINT));
        // Случай и правило — пункты 104 и 86: что решает жребий, что нет, и где жребий не ровный.
        list.add(article("luck", true, GalacticEventService.STANDING_BIAS));
        return list;
    }

    private RulesBookDto.Article article(String code, boolean deviates, Object... numbers) {
        String key = "rules.article." + code;
        return new RulesBookDto.Article(code, messages.get(key + ".title"), messages.get(key + ".text", numbers),
                deviates ? messages.get(key + ".deviation", numbers) : null);
    }
}
