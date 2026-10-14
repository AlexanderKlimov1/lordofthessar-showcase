package com.sddnw.server.web;

import com.sddnw.server.domain.enums.AiSkill;
import com.sddnw.server.dto.AiSkillDto;
import com.sddnw.server.service.AccountService;
import com.sddnw.server.service.DifficultyRules;
import com.sddnw.server.service.GameRecordService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Уровни сложности — field-backlog, пункты 9, 10 и 87: что делает ИИ каждого уровня (справочник,
 * открыт всем) и какой уровень предложить следующей партией (по истории своей записи).
 */
@RestController
public class DifficultyController {

    private final AccountService accountService;
    private final GameRecordService records;

    public DifficultyController(AccountService accountService, GameRecordService records) {
        this.accountService = accountService;
        this.records = records;
    }

    @GetMapping("/api/reference/ai-skills")
    public List<AiSkillDto> skills() {
        return AiSkill.ladder().stream()
                .map(skill -> new AiSkillDto(skill.name(), skill.getBuild().name(), skill.getRecommendedResearch(),
                        skill.getPurchasesPerTurn(), skill.getCoalitions(), skill.getOpeningBook()))
                .toList();
    }

    /** Подсказка уровня: пусто — прошлой партии нет или она была ровной. */
    @GetMapping("/api/auth/difficulty-suggestion")
    public Map<String, Object> suggestion(@AccountToken String accountToken) {
        DifficultyRules.Suggestion suggestion = records.difficultyFor(accountService.require(accountToken).getId());
        if (suggestion == null) {
            return Map.of();
        }
        Map<String, Object> answer = new java.util.LinkedHashMap<>();
        answer.put("last", suggestion.last().name());
        answer.put("suggested", suggestion.suggested().name());
        answer.put("reason", suggestion.reason().name());
        if (suggestion.turn() != null) {
            answer.put("turn", suggestion.turn());
        }
        return answer;
    }
}
