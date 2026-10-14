package com.sddnw.server.dto;

import java.util.List;

/** Предложения игроку (ждут его ответа) и от игрока (ждут чужого) — field-backlog, пункт 182. */
public record DiplomacyProposalsDto(List<DiplomacyProposalDto> incoming, List<DiplomacyProposalDto> outgoing) {
}
