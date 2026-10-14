package com.sddnw.server.service;

import com.sddnw.server.domain.entity.EmpireActivityEntity;
import com.sddnw.server.repository.EmpireActivityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Счётчик того, чем империя пользовалась, — этап 1 балансировки
 * (`balance-metrics-works.txt`).
 * <p>
 * <b>Зачем.</b> Ценность стороны расы измерима ровно настолько, насколько работает
 * механика, к которой эта сторона привязана. Телепаты без единого подчинения и
 * военачальники без единого боя получат в замерах ноль — но ноль этот значит «игра их не
 * тронула», а не «они слабы», и балансировать по нему нельзя. Счётчик и отличает одно от
 * другого: пока он пуст, цена помечается «ИИ не измеряет» (п. 6 плана).
 * <p>
 * Пишется прямо в тех местах, где событие и происходит, — одной строкой. Бои, договоры и
 * лидеры видны и без него (они лежат в своих таблицах), но десант, подчинение, кража и
 * перелёт следа не оставляют: они меняют мир и исчезают.
 */
@Service
public class EmpireActivityService {

    /** Империя выслала флот в другую систему — п. 8. */
    public static final String FLIGHT = "FLIGHT";

    /** Империя высадила десант — п. 12; {@link #CAPTURE} — сколько раз он взял колонию. */
    public static final String INVASION = "INVASION";

    public static final String CAPTURE = "CAPTURE";

    /**
     * У империи ОТНЯЛИ колонию десантом — п. 12.
     * <p>
     * Зеркало {@link #CAPTURE}, и заведён он ради мерила наземного боя (этап 2): пока оно
     * считало одни захваты, половина стороны «бойцы» была неизмерима по построению. Хорошие
     * бойцы проявляются не тем, что империя чаще берёт чужое, — ИИ высаживается только при
     * полуторном перевесе, то есть когда победа уже решена, — а тем, что у НЕЁ реже берут.
     * Такого счётчика в приборе не было вовсе.
     * <p>
     * Считается только десант. Подчинение телепатами ({@link #MIND_CONTROL}) колонию тоже
     * отнимает, но к наземному бою отношения не имеет и живёт своим счётчиком: иначе цена
     * телепатов ушла бы в мерило бойцов.
     */
    public static final String COLONY_LOST = "COLONY_LOST";

    /** Телепаты подчинили колонию без десанта — п. 7, п. 12. */
    public static final String MIND_CONTROL = "MIND_CONTROL";

    /** Разведка довела задание до конца — п. 13. */
    public static final String ESPIONAGE = "ESPIONAGE";

    /**
     * Империя убила космическое чудище — п. 11.1.
     * <p>
     * Считается ради балансировки: чудище сторожит систему, и раса, которой оно по зубам
     * раньше прочих, расселяется шире. Без счётчика это было бы не отличить от везения с
     * картой.
     */
    public static final String MONSTER = "MONSTER";

    /** Империя объявила войну — п. 15. */
    public static final String WAR = "WAR";

    /**
     * Империя участвовала в бою — п. 8.
     * <p>
     * Считается и быстрый «авто», и тактический бой: счётчик отвечает на вопрос «воевала
     * ли империя вообще», а не «по какому пути посчитан бой».
     */
    public static final String BATTLE = "BATTLE";

    /** Империя подписала договор — п. 15. */
    public static final String TREATY = "TREATY";

    /**
     * Обмен технологиями — п. 15. Считается ОБЕИМ сторонам: обмен это событие двоих.
     * <p>
     * Заведён вместе с тем, как обмен появился у ИИ: до того он существовал только для
     * игрока, и для замера был мёртв. Пустой счётчик у стороны расы значит «игра механику
     * не тронула», а не «сторона слаба», — без него подорожание неизобретательности мерило
     * бы покалеченную механику.
     */
    public static final String TECH_EXCHANGE = "TECH_EXCHANGE";

    /** Империя наняла лидера — п. 6. */
    public static final String LEADER = "LEADER";

    /** Империя основала колонию или поставила заставу — п. 4.1. */
    public static final String COLONIZED = "COLONIZED";

    public static final String OUTPOST = "OUTPOST";

    /**
     * Империи досталось доброе или дурное случайное событие — field-backlog, пункт 36: сколько
     * бед и удач приходится на империю за партию и как это зависит от её места.
     */
    public static final String EVENT_GOOD = "EVENT_GOOD";
    public static final String EVENT_BAD = "EVENT_BAD";

    /**
     * Первые встречи с механиками — field-backlog, пункт 93: знакомство, находка, совет и
     * абордаж прежде не считались вовсе, а без них «где в партии тянется пустота» не видно.
     */
    public static final String CONTACT = "CONTACT";
    public static final String FIND = "FIND";
    public static final String COUNCIL = "COUNCIL";
    public static final String BOARDING = "BOARDING";

    private final EmpireActivityRepository repository;
    private final MilestoneService milestones;
    private final com.sddnw.server.repository.GameRepository games;

    public EmpireActivityService(EmpireActivityRepository repository, MilestoneService milestones,
                                 com.sddnw.server.repository.GameRepository games) {
        this.repository = repository;
        this.milestones = milestones;
        this.games = games;
    }

    /**
     * Главный момент партии — в летопись (field-backlog, пункт 34). Стоит здесь, рядом со
     * счётчиком, потому что счётчики уже стоят ровно там, где событие происходит (на этом
     * они четырежды учились, см. правила проекта), — и летопись попадает туда же.
     */
    public void moment(UUID gameId, com.sddnw.server.domain.entity.PlayerEntity player, Integer turn,
                       String kind, MessageKey message) {
        milestones.note(gameId, player, turn, kind, message);
    }

    /**
     * Отмечает, что империя сделала это ещё раз.
     * <p>
     * Ход партии считается под замком (одна партия — один счётчик за раз), поэтому
     * «прочитать и прибавить» здесь безопасно и обходится дешевле, чем отдельная таблица
     * событий: строк ровно столько, сколько видов действий, а не сколько действий.
     * <p>
     * Ошибка счётчика не должна ронять ход: если что-то пойдёт не так, партия важнее
     * замера — поэтому запись идёт своей транзакцией и молча пропускается для игрока,
     * которого уже нет.
     */
    @Transactional
    public void record(UUID gameId, UUID playerId, String code) {
        if (gameId == null || playerId == null) {
            return;
        }
        EmpireActivityEntity row = repository.findByPlayerIdAndCode(playerId, code)
                .orElseGet(() -> {
                    EmpireActivityEntity fresh = new EmpireActivityEntity();
                    fresh.setGameId(gameId);
                    fresh.setPlayerId(playerId);
                    fresh.setCode(code);
                    fresh.setTimes(0);
                    // Ход первой встречи (пункт 93). Внутри хода партия уже в контексте Hibernate,
                    // и поиск по ключу запроса не делает; вне хода — один запрос на ПЕРВОЕ событие.
                    fresh.setFirstTurn(games.findById(gameId)
                            .map(com.sddnw.server.domain.entity.GameEntity::getTurn).orElse(null));
                    return fresh;
                });
        row.setTimes(row.getTimes() + 1);
        repository.save(row);
    }
}
