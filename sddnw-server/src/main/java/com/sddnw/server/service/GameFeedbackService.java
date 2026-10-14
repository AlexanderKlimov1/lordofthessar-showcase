package com.sddnw.server.service;

import com.sddnw.server.domain.entity.history.AccountEntity;
import com.sddnw.server.domain.entity.history.GameFeedbackEntity;
import com.sddnw.server.domain.entity.history.GameRecordEntity;
import com.sddnw.server.dto.GameFeedbackDto;
import com.sddnw.server.dto.GameFeedbackRequest;
import com.sddnw.server.repository.history.GameFeedbackRepository;
import com.sddnw.server.repository.history.GameRecordRepository;
import com.sddnw.server.web.error.BadRequestException;
import com.sddnw.server.web.error.ConflictException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Отзыв после партии — field-backlog, пункты 46 и 187.
 * <p>
 * Короткий вопрос в сцене конца партии: одна оценка, что понравилось больше всего и
 * необязательная строка. Это и бесплатная воронка проблем для разработки, и проверка того,
 * какие виды удовольствия игра даёт на деле (пункт 78), — ответ ложится рядом с историей партии.
 * <p>
 * <b>Не чаще раза в {@link #ASK_EVERY} партии</b>: вопрос после каждой партии учит закрывать его
 * не глядя. Отсчёт идёт от последнего ответа ИЛИ отказа — отказ запоминается так же, как ответ.
 * Первый раз спрашивается после первой же законченной партии: первое впечатление ценнее всего.
 */
@Service
public class GameFeedbackService {

    private static final Logger log = LoggerFactory.getLogger(GameFeedbackService.class);

    /** Через сколько законченных партий после прошлого ответа спрашивать снова. */
    public static final int ASK_EVERY = 3;

    /** Что понравилось больше всего — вопрос пункта 187. */
    public static final Set<String> LIKED = Set.of("HARD_WIN", "DISCOVERY", "BUILDING", "PEOPLE");

    private final GameFeedbackRepository feedback;
    private final GameRecordRepository records;
    private final AccountService accountService;

    public GameFeedbackService(GameFeedbackRepository feedback, GameRecordRepository records,
                               AccountService accountService) {
        this.feedback = feedback;
        this.records = records;
        this.accountService = accountService;
    }

    /**
     * Спросить ли отзыв об этой партии: партия в истории записи есть (то есть законченная), об
     * ней ещё не спрашивали, и с прошлого ответа или отказа прошло не меньше {@link #ASK_EVERY}
     * партий.
     */
    @Transactional(value = "historyTransactionManager", readOnly = true)
    public Boolean ask(String accountToken, UUID gameId) {
        AccountEntity account = accountService.require(accountToken);
        if (!Boolean.TRUE.equals(records.existsByAccountIdAndGameId(account.getId(), gameId))
                || Boolean.TRUE.equals(feedback.existsByAccountIdAndGameId(account.getId(), gameId))) {
            return Boolean.FALSE;
        }
        return feedback.findFirstByAccountIdOrderByCreatedAtDesc(account.getId())
                .map(last -> records.findAllByAccountIdOrderByFinishedAtDesc(account.getId()).stream()
                        .filter(record -> record.getFinishedAt().isAfter(last.getCreatedAt()))
                        .count() >= ASK_EVERY)
                .orElse(Boolean.TRUE);
    }

    /** Записать ответ или отказ (пустая оценка). Об одной партии отвечают один раз. */
    @Transactional("historyTransactionManager")
    public void submit(String accountToken, GameFeedbackRequest request) {
        AccountEntity account = accountService.require(accountToken);
        GameRecordEntity record = records.findAllByAccountIdOrderByFinishedAtDesc(account.getId()).stream()
                .filter(candidate -> candidate.getGameId().equals(request.gameId()))
                .findFirst()
                .orElseThrow(() -> new ConflictException("feedback.notFinished"));
        if (Boolean.TRUE.equals(feedback.existsByAccountIdAndGameId(account.getId(), request.gameId()))) {
            throw new ConflictException("feedback.already");
        }
        if (request.liked() != null && !LIKED.contains(request.liked())) {
            throw new BadRequestException("feedback.liked");
        }
        GameFeedbackEntity entity = new GameFeedbackEntity();
        entity.setAccountId(account.getId());
        entity.setGameId(request.gameId());
        entity.setRating(request.rating());
        // Без оценки — это отказ, и ни выбора, ни строки у отказа не бывает.
        entity.setLiked(request.rating() == null ? null : request.liked());
        String comment = request.comment() == null ? null : request.comment().strip();
        entity.setComment(request.rating() == null || comment == null || comment.isEmpty() ? null : comment);
        entity.setOutcome(record.getOutcome());
        entity.setVictoryKind(record.getVictoryKind());
        entity.setGalaxySize(record.getGalaxySize());
        entity.setEmpires(record.getEmpires());
        entity.setTurn(record.getTurn());
        entity.setCreatedAt(OffsetDateTime.now());
        feedback.save(entity);
        log.info("Отзыв о партии {}: оценка {}, понравилось {}", request.gameId(), entity.getRating(),
                entity.getLiked());
    }

    /** Отзывы — только администратору; отказы не показываются, им читать нечего. */
    @Transactional(value = "historyTransactionManager", readOnly = true)
    public List<GameFeedbackDto> list(String accountToken) {
        accountService.requireAdmin(accountToken);
        return feedback.findAllByRatingIsNotNullOrderByCreatedAtDesc().stream()
                .map(row -> new GameFeedbackDto(row.getRating(), row.getLiked(), row.getComment(),
                        row.getOutcome(), row.getVictoryKind(), row.getGalaxySize(), row.getEmpires(),
                        row.getTurn(), row.getCreatedAt()))
                .toList();
    }
}
