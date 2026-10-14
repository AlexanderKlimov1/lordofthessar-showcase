package com.sddnw.server.web;

import com.sddnw.server.dto.GameFeedbackDto;
import com.sddnw.server.dto.GameFeedbackRequest;
import com.sddnw.server.service.GameFeedbackService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Отзыв после партии — field-backlog, пункты 46 и 187. */
@RestController
@RequestMapping("/api/feedback")
public class GameFeedbackController {

    private final GameFeedbackService feedback;

    public GameFeedbackController(GameFeedbackService feedback) {
        this.feedback = feedback;
    }

    /** Спросить ли отзыв об этой партии — сцена конца партии спрашивает при открытии. */
    @GetMapping("/ask")
    public Map<String, Boolean> ask(@AccountToken String accountToken, @RequestParam UUID gameId) {
        return Map.of("ask", feedback.ask(accountToken, gameId));
    }

    /** Ответ или отказ (пустая оценка). */
    @PostMapping
    public Map<String, Boolean> submit(@AccountToken String accountToken,
                                       @Valid @RequestBody GameFeedbackRequest request) {
        feedback.submit(accountToken, request);
        return Map.of("saved", Boolean.TRUE);
    }

    /** Все отзывы — только администратору. */
    @GetMapping
    public List<GameFeedbackDto> list(@AccountToken String accountToken) {
        return feedback.list(accountToken);
    }
}
