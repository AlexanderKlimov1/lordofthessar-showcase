package com.sddnw.server.service;

import com.sddnw.server.domain.entity.history.AccountEntity;
import com.sddnw.server.domain.entity.history.ProblemReportEntity;
import com.sddnw.server.domain.enums.ProblemAnswer;
import com.sddnw.server.dto.ProblemAnswerDto;
import com.sddnw.server.dto.ProblemAnswerRequest;
import com.sddnw.server.dto.ProblemReportDto;
import com.sddnw.server.dto.ProblemReportRequest;
import com.sddnw.server.repository.history.AccountRepository;
import com.sddnw.server.repository.history.ProblemReportRepository;
import com.sddnw.server.web.error.BadRequestException;
import com.sddnw.server.web.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Сообщения о проблеме — field-backlog, пункт 157 (P0): единственный канал, по которому
 * тестер скажет, что сломалось.
 * <p>
 * Сообщить может любой вошедший, и гость тоже: гость и есть новичок, чьё «сломалось»
 * дороже всего. Отчёт собирает клиент — партию, ход, экран, версию и последние ошибки, —
 * и игрок дописывает строку. У хозяина — страница «Сообщения о проблемах» (только
 * администратору) и строка в журнале сервера на каждое сообщение: журнал он читает и без
 * страницы.
 * <p>
 * Предел частоты — по записи ({@link ProblemReportThrottle}): сообщение ложится в базу, и
 * без предела это ещё один канал, которым чужой скрипт заваливал бы её.
 * <p>
 * <b>Круг замыкается ответом</b> (пункт 158): хозяин отвечает «исправлено в версии такой-то»
 * или «не будем исправлять» с причиной, и написавший видит это один раз, при следующем
 * входе. Парадокс восстановления работает, только когда человек видит, что его услышали:
 * тестер, которому ответили, остаётся и пишет снова.
 */
@Service
public class ProblemReportService {

    private static final Logger log = LoggerFactory.getLogger(ProblemReportService.class);

    private final ProblemReportRepository reports;
    private final AccountRepository accounts;
    private final AccountService accountService;
    private final ProblemReportThrottle throttle;
    private final NoticeService notices;

    public ProblemReportService(ProblemReportRepository reports, AccountRepository accounts,
                                AccountService accountService, ProblemReportThrottle throttle,
                                NoticeService notices) {
        this.reports = reports;
        this.accounts = accounts;
        this.accountService = accountService;
        this.throttle = throttle;
        this.notices = notices;
    }

    @Transactional("historyTransactionManager")
    public void report(String accountToken, ProblemReportRequest request) {
        AccountEntity account = accountService.require(accountToken);
        String key = account.getId().toString();
        throttle.requireAllowed(key);

        ProblemReportEntity report = new ProblemReportEntity();
        report.setAccountId(account.getId());
        report.setGameId(request.gameId());
        report.setTurn(request.turn());
        report.setClientVersion(request.clientVersion());
        report.setScreen(request.screen());
        report.setText(request.text().trim());
        report.setContext(request.context());
        report.setResolved(Boolean.FALSE);
        report.setCreatedAt(OffsetDateTime.now());
        reports.save(report);
        throttle.reported(key);
        log.warn("Сообщение о проблеме от {} (партия {}, ход {}, экран {}): {}", account.getLogin(),
                request.gameId(), request.turn(), request.screen(), report.getText());
    }

    /** Все сообщения, свежие сверху, — только администратору. */
    @Transactional(value = "historyTransactionManager", readOnly = true)
    public List<ProblemReportDto> list(String accountToken) {
        accountService.requireAdmin(accountToken);
        List<ProblemReportEntity> rows = reports.findAllByOrderByCreatedAtDesc();
        Map<UUID, AccountEntity> byId = accounts.findAllById(rows.stream()
                        .map(ProblemReportEntity::getAccountId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(AccountEntity::getId, Function.identity()));
        return rows.stream().map(row -> toDto(row, byId.get(row.getAccountId()))).toList();
    }

    /** Сколько сообщений ждут разбора — число у ссылки в меню хозяина. */
    @Transactional(value = "historyTransactionManager", readOnly = true)
    public Long pending(String accountToken) {
        accountService.requireAdmin(accountToken);
        return reports.countByResolvedFalse();
    }

    /** Отметить разобранным (или вернуть в работу) — только администратору. */
    @Transactional("historyTransactionManager")
    public ProblemReportDto resolve(String accountToken, UUID reportId, Boolean resolved) {
        accountService.requireAdmin(accountToken);
        ProblemReportEntity row = reports.findById(reportId)
                .orElseThrow(() -> new NotFoundException("problem.notFound", reportId));
        row.setResolved(Boolean.TRUE.equals(resolved));
        if (!row.getResolved()) {
            // Вернули в работу — прежний ответ снят: «исправлено» о том, что снова сломано,
            // написавшему врало бы. Если он его ещё не прочёл, он его и не прочтёт.
            clearAnswer(row);
        }
        reports.save(row);
        return toDto(row, accounts.findById(row.getAccountId()).orElse(null));
    }

    /**
     * Ответить написавшему — только администратору. «Исправлено» помечается версией (пусто —
     * текущая, та, что в меню), «не будем исправлять» требует причины словами: ответ без
     * причины читается отмахиванием. Ответ заодно отмечает сообщение разобранным; новый ответ
     * заменяет прежний и показывается заново.
     */
    @Transactional("historyTransactionManager")
    public ProblemReportDto answer(String accountToken, UUID reportId, ProblemAnswerRequest request) {
        accountService.requireAdmin(accountToken);
        ProblemReportEntity row = reports.findById(reportId)
                .orElseThrow(() -> new NotFoundException("problem.notFound", reportId));
        String text = request.text() == null || request.text().isBlank() ? null : request.text().trim();
        if (request.kind() == ProblemAnswer.WONT_FIX && text == null) {
            throw new BadRequestException("problem.answerReason");
        }
        boolean fixed = request.kind() == ProblemAnswer.FIXED;
        boolean ownVersion = request.version() != null && !request.version().isBlank();
        row.setAnswerKind(request.kind());
        row.setAnswerVersion(!fixed ? null : ownVersion ? request.version().trim() : notices.version());
        row.setAnswerText(text);
        row.setAnsweredAt(OffsetDateTime.now());
        row.setAnswerSeen(Boolean.FALSE);
        row.setResolved(Boolean.TRUE);
        reports.save(row);
        return toDto(row, accounts.findById(row.getAccountId()).orElse(null));
    }

    /** Ответы на свои сообщения, которых написавший ещё не видел, — старые первыми. */
    @Transactional(value = "historyTransactionManager", readOnly = true)
    public List<ProblemAnswerDto> answers(String accountToken) {
        AccountEntity account = accountService.require(accountToken);
        return reports.findAllByAccountIdAndAnsweredAtNotNullAndAnswerSeenFalseOrderByCreatedAtAsc(account.getId())
                .stream()
                .map(row -> new ProblemAnswerDto(row.getId(), row.getCreatedAt(), excerpt(row.getText()),
                        row.getAnswerKind(), row.getAnswerVersion(), row.getAnswerText()))
                .toList();
    }

    /** Написавший прочёл ответы — одна строка, один раз. */
    @Transactional("historyTransactionManager")
    public void answersSeen(String accountToken) {
        AccountEntity account = accountService.require(accountToken);
        List<ProblemReportEntity> rows =
                reports.findAllByAccountIdAndAnsweredAtNotNullAndAnswerSeenFalseOrderByCreatedAtAsc(account.getId());
        rows.forEach(row -> row.setAnswerSeen(Boolean.TRUE));
        reports.saveAll(rows);
    }

    /** Начало сообщения — по нему написавший узнаёт, о чём речь, через неделю. */
    private static String excerpt(String text) {
        String line = text.strip().replaceAll("\\s+", " ");
        return line.length() <= 80 ? line : line.substring(0, 79).stripTrailing() + "…";
    }

    private static void clearAnswer(ProblemReportEntity row) {
        row.setAnswerKind(null);
        row.setAnswerVersion(null);
        row.setAnswerText(null);
        row.setAnsweredAt(null);
        row.setAnswerSeen(Boolean.FALSE);
    }

    private ProblemReportDto toDto(ProblemReportEntity row, AccountEntity account) {
        return new ProblemReportDto(row.getId(), row.getAccountId(),
                account == null ? null : account.getName(),
                account == null ? null : account.getRole().name(),
                row.getGameId(), row.getTurn(), row.getClientVersion(), row.getScreen(),
                row.getText(), row.getContext(), row.getResolved(), row.getCreatedAt(),
                row.getAnswerKind(), row.getAnswerVersion(), row.getAnswerText(), row.getAnsweredAt(),
                row.getAnswerSeen());
    }
}
