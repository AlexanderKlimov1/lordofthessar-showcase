package com.sddnw.server.service;

import com.sddnw.server.domain.entity.history.AccountEntity;
import com.sddnw.server.domain.entity.history.FunnelStepEntity;
import com.sddnw.server.dto.FunnelStepDto;
import com.sddnw.server.dto.FunnelStepRequest;
import com.sddnw.server.repository.history.FunnelStepRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Воронка новичка — field-backlog, пункт 2 (P0): где именно уходят пришедшие.
 * <p>
 * Шаги шлёт клиент — он один знает, какой экран открыт, какой шаг обучения на виду и что
 * было в итогах хода, — а сервер их только складывает. Разбирает журнал
 * {@code tools/guest_funnel.py}: последняя строка посетителя и есть место ухода.
 * <p>
 * <b>Журнал не должен мешать игре.</b> Отказ здесь не нужен никому: сбой записи
 * проглатывается, а клиент шлёт шаги, не дожидаясь ответа. Повтор того же шага подряд
 * (перерисовка, обновление страницы) не пишется, а у одного посетителя строк не больше
 * {@link #STEPS_PER_VISITOR}: иначе цикл в чужом скрипте заваливал бы базу.
 */
@Service
public class FunnelService {

    private static final Logger log = LoggerFactory.getLogger(FunnelService.class);

    /** Больше шагов у одного браузера не бывает и за десяток партий. */
    static final long STEPS_PER_VISITOR = 5000;

    private final FunnelStepRepository steps;
    private final AccountService accountService;

    public FunnelService(FunnelStepRepository steps, AccountService accountService) {
        this.steps = steps;
        this.accountService = accountService;
    }

    /**
     * Записывает шаг посетителя.
     *
     * @param accountToken пропуск записи, если человек вошёл; чужой или протухший пропуск
     *                     шагу не мешает — он пишется без записи
     */
    @Transactional("historyTransactionManager")
    public void record(FunnelStepRequest request, String accountToken) {
        AccountEntity account = accountOf(accountToken);
        FunnelStepEntity last = steps.findFirstByVisitorOrderByCreatedAtDesc(request.visitor()).orElse(null);
        if (last != null && last.getStep().equals(request.step())
                && Objects.equals(last.getGameId(), request.gameId())
                && Objects.equals(last.getTurn(), request.turn())
                && Objects.equals(last.getDetail(), request.detail())) {
            return;
        }
        if (steps.countByVisitor(request.visitor()) >= STEPS_PER_VISITOR) {
            return;
        }
        FunnelStepEntity step = new FunnelStepEntity();
        step.setVisitor(request.visitor());
        step.setAccountId(account == null ? null : account.getId());
        step.setRole(account == null ? null : account.getRole().name());
        step.setStep(request.step());
        step.setGameId(request.gameId());
        step.setTurn(request.turn());
        step.setDetail(request.detail());
        step.setCreatedAt(OffsetDateTime.now());
        steps.save(step);
    }

    /** Шаги за последние дни — для разбора; только администратору. */
    @Transactional(value = "historyTransactionManager", readOnly = true)
    public List<FunnelStepDto> steps(String accountToken, Integer days) {
        accountService.requireAdmin(accountToken);
        int span = days == null ? 30 : Math.max(1, Math.min(days, 365));
        return steps.findAllByCreatedAtAfterOrderByCreatedAtAsc(OffsetDateTime.now().minusDays(span)).stream()
                .map(step -> new FunnelStepDto(step.getVisitor(), step.getAccountId(), step.getRole(),
                        step.getStep(), step.getGameId(), step.getTurn(), step.getDetail(), step.getCreatedAt()))
                .toList();
    }

    /** Запись по пропуску, если он есть и жив; иначе шаг пишется без записи. */
    private AccountEntity accountOf(String accountToken) {
        if (accountToken == null || accountToken.isBlank()) {
            return null;
        }
        try {
            return accountService.require(accountToken);
        } catch (RuntimeException cause) {
            log.debug("Шаг воронки с негодным пропуском пишется без записи: {}", cause.getMessage());
            return null;
        }
    }
}
