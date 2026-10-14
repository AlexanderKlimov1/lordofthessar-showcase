package com.sddnw.server.web;

import com.sddnw.server.dto.ProblemAnswerDto;
import com.sddnw.server.dto.ProblemAnswerRequest;
import com.sddnw.server.dto.ProblemReportDto;
import com.sddnw.server.dto.ProblemReportRequest;
import com.sddnw.server.service.ProblemReportService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Сообщения о проблеме — field-backlog, пункт 157: пишет любой вошедший (и гость), читает
 * и отмечает разобранным только администратор.
 */
@RestController
@RequestMapping("/api/problems")
public class ProblemReportController {

    private final ProblemReportService problems;

    public ProblemReportController(ProblemReportService problems) {
        this.problems = problems;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void report(@Valid @RequestBody ProblemReportRequest request, @AccountToken String accountToken) {
        problems.report(accountToken, request);
    }

    @GetMapping
    public List<ProblemReportDto> list(@AccountToken String accountToken) {
        return problems.list(accountToken);
    }

    /** Сколько ждут разбора — число у ссылки в меню хозяина. */
    @GetMapping("/pending")
    public Long pending(@AccountToken String accountToken) {
        return problems.pending(accountToken);
    }

    /** Ответ хозяина написавшему — пункт 158: исправлено в версии или «не будем» с причиной. */
    @PostMapping("/{reportId}/answer")
    public ProblemReportDto answer(@PathVariable UUID reportId, @AccountToken String accountToken,
                                   @Valid @RequestBody ProblemAnswerRequest request) {
        return problems.answer(accountToken, reportId, request);
    }

    /** Ответы на свои сообщения, которых вошедший ещё не видел. */
    @GetMapping("/answers")
    public List<ProblemAnswerDto> answers(@AccountToken String accountToken) {
        return problems.answers(accountToken);
    }

    @PostMapping("/answers/seen")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void answersSeen(@AccountToken String accountToken) {
        problems.answersSeen(accountToken);
    }

    @PostMapping("/{reportId}/resolve")
    public ProblemReportDto resolve(@PathVariable UUID reportId, @AccountToken String accountToken,
                                    @RequestParam(defaultValue = "true") Boolean resolved) {
        return problems.resolve(accountToken, reportId, resolved);
    }
}
