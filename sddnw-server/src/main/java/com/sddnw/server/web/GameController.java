package com.sddnw.server.web;

import com.sddnw.server.domain.entity.history.AccountEntity;
import com.sddnw.server.domain.enums.AccountRole;
import com.sddnw.server.dto.VictoryClaimDto;
import com.sddnw.server.dto.VacationRequest;
import com.sddnw.server.dto.AdvanceTurnsRequest;
import com.sddnw.server.dto.AdvanceTurnsResponse;
import com.sddnw.server.dto.BalanceTelemetryDto;
import com.sddnw.server.dto.CreateGameRequest;
import com.sddnw.server.dto.CreateGameResponse;
import com.sddnw.server.dto.EndTurnRequest;
import com.sddnw.server.dto.EndTurnResponse;
import com.sddnw.server.dto.GalaxyMapDto;
import com.sddnw.server.dto.GameDetailsDto;
import com.sddnw.server.dto.GameEndingDto;
import com.sddnw.server.dto.GameSaveDto;
import com.sddnw.server.dto.GameSummaryDto;
import com.sddnw.server.dto.JoinGameRequest;
import com.sddnw.server.dto.SaveGameRequest;
import com.sddnw.server.dto.StartGameRequest;
import com.sddnw.server.dto.TurnReportDto;
import com.sddnw.server.service.AccountService;
import com.sddnw.server.service.BalanceTelemetryService;
import com.sddnw.server.service.ConcessionService;
import com.sddnw.server.service.GameEndingService;
import com.sddnw.server.service.GameService;
import com.sddnw.server.service.GuestGameRules;
import com.sddnw.server.service.TurnBatchService;
import com.sddnw.server.service.stub.TurnService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.UUID;

/**
 * Партия: лобби, старт, ходы, карта и сохранения — п. 3.1, п. 3.2, п. 11.1.
 * <p>
 * Действия внутри партии живут в своих контроллерах: колонии — {@link ColonyController},
 * исследования — {@link ResearchController}.
 */
@RestController
@RequestMapping("/api/games")
@Validated
public class GameController {

    private final GameService gameService;
    private final AccountService accountService;
    private final BalanceTelemetryService balanceTelemetryService;
    private final TurnBatchService turnBatchService;
    private final GameEndingService gameEndingService;
    private final TurnService turnService;
    private final ConcessionService concessionService;
    private final com.sddnw.server.service.VacationService vacationService;
    private final com.sddnw.server.service.RematchService rematchService;
    private final com.sddnw.server.service.ModService modService;

    public GameController(GameService gameService, AccountService accountService,
                          BalanceTelemetryService balanceTelemetryService,
                          TurnBatchService turnBatchService, GameEndingService gameEndingService,
                          TurnService turnService, ConcessionService concessionService,
                          com.sddnw.server.service.VacationService vacationService,
                          com.sddnw.server.service.RematchService rematchService,
                          com.sddnw.server.service.ModService modService) {
        this.modService = modService;
        this.rematchService = rematchService;
        this.vacationService = vacationService;
        this.concessionService = concessionService;
        this.gameEndingService = gameEndingService;
        this.turnService = turnService;
        this.gameService = gameService;
        this.accountService = accountService;
        this.balanceTelemetryService = balanceTelemetryService;
        this.turnBatchService = turnBatchService;
    }

    /** Список игр, видимый всем, кто видит IP сервера — п. 3.1. */
    @GetMapping
    public List<GameSummaryDto> listGames(@RequestParam(defaultValue = "false") Boolean openOnly) {
        return gameService.listGames(openOnly);
    }

    /**
     * Создание игры. Размер галактики необязателен, по умолчанию Huge.
     * <p>
     * До партии игрок называет себя — п. 3.1: пропуск учётной записи проверяется здесь,
     * на входе в партию. Дальше внутри партии ходит уже пропуск игрока: партия живёт
     * своей жизнью и об учётных записях ничего не знает.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CreateGameResponse createGame(@Valid @RequestBody CreateGameRequest request,
                                         @AccountToken String accountToken) {
        AccountEntity account = accountService.require(accountToken);
        // Гостевая партия — backlog-promo, пункт 1: настройки назначает сервер, а не окно
        // новой игры. Проверять их на клиенте мало — гость шлёт запрос сам.
        boolean guest = account.getRole() == AccountRole.GUEST;
        CreateGameRequest effective = guest ? GuestGameRules.restrict(request) : request;
        // Галактика недели — пункт 146: только зарегистрированным, условия одни на всех.
        if (Boolean.TRUE.equals(request.weekly())) {
            accountService.requireRegistered(accountToken);
            effective = com.sddnw.server.service.WeeklyGalaxyRules.restrict(request,
                    com.sddnw.server.service.WeeklyGalaxyRules.currentWeek());
        }
        // Тесный старт — backlog-promo, пункт 5: только у гостевой партии. Администратор вправе
        // завести партию по гостевым правилам для замера (field-backlog, пункт 190) — со своим
        // зерном и наблюдателем, которых гостю не дают.
        boolean guestRules = guest || (account.getRole() == AccountRole.ADMIN
                && Boolean.TRUE.equals(request.guestRules()));
        // Свой набор справочников (пункт 42) — только основателю и только свой; гостю — никогда.
        com.sddnw.server.domain.entity.history.ModSetEntity mod = null;
        if (request.modId() != null) {
            if (guest) {
                throw new com.sddnw.server.web.error.ForbiddenException("mods.founderOnly");
            }
            mod = modService.own(accountService.requireFounder(accountToken).getId(), request.modId());
        }
        CreateGameResponse created = gameService.createGame(effective, account.getId(), guestRules);
        if (mod != null) {
            gameService.makeModded(created.game().id());
            modService.attach(created.game().id(), mod);
        }
        return created;
    }

    @GetMapping("/{gameId}")
    public GameDetailsDto getGame(@PathVariable UUID gameId) {
        return gameService.getGame(gameId);
    }

    /**
     * Партии, где у этой записи есть место, — «вернуться в партию» в главном меню (п. 3).
     * <p>
     * Стоит ДО {@code /{gameId}} нарочно: иначе «mine» разбиралось бы как опознаватель
     * партии и отвечало бы «не найдена».
     */
    @GetMapping("/mine")
    public List<GameSummaryDto> myGames(@AccountToken String accountToken) {
        return gameService.myGames(accountService.require(accountToken).getId());
    }

    /**
     * Итоги оконченной партии — field-backlog, пункт 33: сцена конца партии.
     * <p>
     * По учётной записи, а не пропуску места: сцену открывают и из «Ваших партий» главного
     * меню, где пропуска может не быть. Партия ещё идёт — 409.
     */
    @GetMapping("/{gameId}/ending")
    public GameEndingDto ending(@PathVariable UUID gameId, @AccountToken String accountToken) {
        return gameEndingService.ending(gameId, accountService.require(accountToken));
    }

    /**
     * Возврат в свою партию — п. 3: запись получает пропуск СВОЕГО места.
     * <p>
     * Нужен после загрузки автосохранения (пропуска там выданы заново) и при входе с
     * другой машины, где хранилища браузера нет.
     */
    @PostMapping("/{gameId}/rejoin")
    public CreateGameResponse rejoin(@PathVariable UUID gameId,
                                     @AccountToken String accountToken) {
        return gameService.rejoin(gameId, accountService.require(accountToken).getId());
    }

    /**
     * Забрать объявленный ход — field-backlog, пункт 128: пока остальные люди не закончили, ход
     * не посчитан, и отметку можно снять. Ход уже посчитан — 409.
     */
    @PostMapping("/{gameId}/turn/undo")
    public GameDetailsDto undoEndTurn(@PathVariable UUID gameId,
                                      @Valid @RequestBody EndTurnRequest request) {
        return gameService.undoEndTurn(gameId, request.accessToken());
    }

    /**
     * Признать поражение — field-backlog, пункт 81: империю дальше ведёт ИИ, а игрок получает
     * сцену конца партии. Против одного ИИ партия на этом кончается; с другими людьми идёт без
     * него. Отвечает состоянием партии ПОСЛЕ признания (своей транзакцией): клиенту нужно знать,
     * окончена ли она.
     */
    /**
     * Можно ли объявить победу — field-backlog, пункт 154: меню «Игра» спрашивает до нажатия и
     * гасит пункт с причиной словами.
     */
    @GetMapping("/{gameId}/victory-claim")
    public VictoryClaimDto victoryClaim(@PathVariable UUID gameId, @AccessToken String accessToken) {
        return concessionService.victoryClaim(gameId, accessToken);
    }

    /**
     * Объявить победу — field-backlog, пункт 154: все прочие живые империи вместе слабее втрое,
     * и хвост партии не обязан доигрываться. Отвечает состоянием партии после объявления.
     */
    @PostMapping("/{gameId}/declare-victory")
    public GameDetailsDto declareVictory(@PathVariable UUID gameId,
                                         @Valid @RequestBody EndTurnRequest request) {
        concessionService.declareVictory(gameId, request.accessToken());
        return gameService.getGame(gameId);
    }

    /**
     * Отпуск в сетевой партии — field-backlog, пункт 177: на {@code days} дней империю ведёт ИИ,
     * партия игрока не ждёт; ноль — вернуться сейчас.
     */
    @PostMapping("/{gameId}/vacation")
    public GameDetailsDto vacation(@PathVariable UUID gameId, @Valid @RequestBody VacationRequest request) {
        vacationService.vacation(gameId, request.accessToken(), request.days());
        return gameService.getGame(gameId);
    }

    @PostMapping("/{gameId}/concede")
    public GameDetailsDto concede(@PathVariable UUID gameId,
                                  @Valid @RequestBody EndTurnRequest request) {
        concessionService.concede(gameId, request.accessToken());
        return gameService.getGame(gameId);
    }

    /**
     * Покинуть партию — backlog-promo, пункт 11: империю ведёт ИИ, партия больше не ждёт
     * ушедшего. Вернуться — {@code rejoin} или любое действие в партии.
     */
    /** «Я здесь» — field-backlog, пункт 127: игрок за столом, ИИ его империю не берёт или отдаёт. */
    @PostMapping("/{gameId}/presence")
    public GameDetailsDto here(@PathVariable UUID gameId,
                               @Valid @RequestBody EndTurnRequest request) {
        return gameService.here(gameId, request.accessToken());
    }

    /** «Играть дальше» после победы — field-backlog, пункт 131. */
    @PostMapping("/{gameId}/play-on")
    public GameDetailsDto playOn(@PathVariable UUID gameId, @Valid @RequestBody EndTurnRequest request) {
        return gameService.playOn(gameId, request.accessToken());
    }

    @PostMapping("/{gameId}/leave")
    public GameDetailsDto leave(@PathVariable UUID gameId,
                                @Valid @RequestBody EndTurnRequest request) {
        return gameService.leave(gameId, request.accessToken());
    }

    /**
     * «Сыграть с людьми» — field-backlog, пункт 65: место за ближайшим столом подбора или новый
     * стол с ожиданием; стол стартует сам, когда за ним двое людей. Гостю — нет: подбор сводит с
     * незнакомыми людьми, а гость в чужие партии не входит (backlog-promo, пункт 1).
     */
    /**
     * Свои в лобби — field-backlog, пункт 148: открытая партия → сколько партий вы уже сыграли
     * с кем-то из её участников. Стоит ДО {@code /{gameId}}, как и «mine».
     */
    @GetMapping("/together")
    public java.util.Map<UUID, Integer> together(@AccountToken String accountToken) {
        return gameService.playedTogether(accountService.require(accountToken).getId());
    }

    @PostMapping("/quick-match")
    public CreateGameResponse quickMatch(@Valid @RequestBody com.sddnw.server.dto.QuickMatchRequest request,
                                         @AccountToken String accountToken) {
        return gameService.quickMatch(accountService.requireRegistered(accountToken).getId(), request.playerName());
    }

    /**
     * Сыграть снова тем же составом — field-backlog, пункт 149: первый нажавший в сцене конца заводит
     * новую партию с теми же настройками, остальные входят в неё тем же нажатием.
     */
    @PostMapping("/{gameId}/rematch")
    public CreateGameResponse rematch(@PathVariable UUID gameId, @AccountToken String accountToken) {
        return rematchService.rematch(gameId, accountService.require(accountToken));
    }

    /** Присоединение к игре — п. 3.2; авторизация нужна так же, как и при создании (п. 3.1). */
    @PostMapping("/{gameId}/join")
    public CreateGameResponse joinGame(@PathVariable UUID gameId,
                                       @Valid @RequestBody JoinGameRequest request,
                                       @AccountToken String accountToken) {
        // В чужую партию гость не входит (backlog-promo, пункт 1): там другие настройки и
        // живые люди, которые вправе знать, с кем играют.
        return gameService.joinGame(gameId, request, accountService.requireRegistered(accountToken).getId());
    }

    /** Старт игры: свободные слоты добираются ИИ-игроками до восьми — п. 3.2. */
    @PostMapping("/{gameId}/start")
    public GameDetailsDto startGame(@PathVariable UUID gameId,
                                    @Valid @RequestBody StartGameRequest request) {
        return gameService.startGame(gameId, request);
    }

    /**
     * Конец хода игрока — п. 11.1: игрок объявляет, что закончил, и ждёт остальных.
     * Галактика считается, когда закончили все люди партии; в ответе видно, посчитан ли
     * ход и кого ещё ждут.
     */
    @PostMapping("/{gameId}/turn/end")
    public EndTurnResponse endTurn(@PathVariable UUID gameId,
                                   @Valid @RequestBody EndTurnRequest request) {
        return gameService.endTurn(gameId, request);
    }

    /**
     * Фазы последнего хода партии в миллисекундах — field-backlog, пункт 166, для проверки
     * бюджета хода ({@code tools/turn_budget.py}); только администратору: это прибор, а не игра.
     */
    @GetMapping("/{gameId}/turn/profile")
    public java.util.Map<String, Double> turnProfile(@PathVariable UUID gameId,
                                                     @AccountToken String accountToken) {
        accountService.requireAdmin(accountToken);
        return turnService.lastTurnProfile(gameId);
    }

    /**
     * Прогнать подряд несколько ходов — этап 0 балансировки
     * (`balance-metrics-works.txt`): прогонам нужны сотни ходов, и дорога к серверу на
     * каждый ход стоит дороже самого хода. Считается всё тем же концом хода.
     */
    @PostMapping("/{gameId}/turn/advance")
    public AdvanceTurnsResponse advanceTurns(@PathVariable UUID gameId,
                                             @Valid @RequestBody AdvanceTurnsRequest request) {
        return turnBatchService.advance(gameId, request);
    }

    /**
     * Телеметрия партии для балансировки — этап 0: условия партии, империи с их расами и
     * стартовым положением и летопись каждой по ходам, одним слепком.
     */
    @GetMapping("/{gameId}/telemetry")
    public BalanceTelemetryDto telemetry(@PathVariable UUID gameId,
                                         @AccessToken String accessToken) {
        return balanceTelemetryService.of(gameId, accessToken);
    }

    /**
     * Что изменилось за последний посчитанный ход — п. 11.1.
     * <p>
     * Ход считается один раз на всех, поэтому у большинства игроков он случается не по их
     * запросу: они узнают о нём подпиской. Этот запрос нужен, когда подписки не было —
     * после перезагрузки страницы или обрыва связи.
     */
    @GetMapping("/{gameId}/turn/report")
    public TurnReportDto turnReport(@PathVariable UUID gameId, @AccessToken String accessToken) {
        return gameService.turnReport(gameId, accessToken);
    }

    /** Сохранение партии — «Игра» → «Сохранить»: состояние на конец завершённого хода. */
    @PostMapping("/{gameId}/save")
    public GameSaveDto saveGame(@PathVariable UUID gameId,
                                @Valid @RequestBody SaveGameRequest request) {
        return gameService.saveGame(gameId, request);
    }

    /**
     * Карта галактики — п. 11.3. Звёзды видны все, названия и владельцы — только по
     * разведанным системам; {@code revealAll} — «Показать галактику» — раскрывает
     * подробности по всей галактике.
     */
    @GetMapping("/{gameId}/map")
    public GalaxyMapDto getMap(@PathVariable UUID gameId,
                               @AccessToken String accessToken,
                               @RequestParam(defaultValue = "false") Boolean revealAll,
                               @org.springframework.web.bind.annotation.RequestHeader(
                                       value = com.sddnw.server.web.AccountTokenResolver.HEADER,
                                       required = false) String accountToken) {
        // Показ всей галактики — только администратору (п. 15, решение хозяина проекта,
        // 03.10.2026): игроку он открывал бы карту, которую положено разведывать флотом.
        // Чужой запрос его не получает молча — карта приходит такой, какой её видит игрок.
        Boolean reveal = Boolean.TRUE.equals(revealAll) && Boolean.TRUE.equals(accountService.isAdmin(accountToken));
        // В партии с другими людьми показ не отнимается, но партия помечается «под наблюдением
        // администратора» для всех — field-backlog, пункт 197.
        if (Boolean.TRUE.equals(reveal)) {
            gameService.markAdminWatch(gameId, accountService.require(accountToken).getId());
        }
        return gameService.getMap(gameId, accessToken, reveal);
    }

    @DeleteMapping("/{gameId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGame(@PathVariable UUID gameId, @AccessToken String accessToken) {
        gameService.deleteGame(gameId, accessToken);
    }

    /**
     * Убрать брошенную партию — распоряжением администратора, без пропуска хозяина.
     * <p>
     * Свой путь, а не тот же самый с другим пропуском: пропуск игрока обязателен по
     * устройству резолвера, и «удалить без него» на том же адресе выразить нечем. А по
     * смыслу это и есть другое действие — хозяйство сервера, как удаление сохранений.
     */
    @DeleteMapping("/{gameId}/admin")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteByAdmin(@PathVariable UUID gameId, @AccountToken String accountToken) {
        accountService.requireAdmin(accountToken);
        gameService.deleteByAdmin(gameId);
    }
}
