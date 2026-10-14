package com.sddnw.server.dto;

import com.sddnw.server.domain.enums.DiplomacyAction;
import com.sddnw.server.domain.enums.DiplomacyTreaty;

import java.util.UUID;

/**
 * Предложение, ждущее ответа человека, — field-backlog, пункт 182.
 *
 * @param deadlineTurn до конца какого хода ждут ответа; дальше решает доверие
 * @param accepted     ответ адресата; пусто — ещё не ответил (передумать можно до конца хода)
 */
public record DiplomacyProposalDto(
        UUID id,
        UUID fromPlayerId,
        String fromName,
        UUID toPlayerId,
        String toName,
        DiplomacyAction action,
        DiplomacyTreaty treaty,
        String offeredTech,
        String offeredTechName,
        String requestedTech,
        String requestedTechName,
        Integer turn,
        Integer deadlineTurn,
        Boolean accepted
) {
}
