package com.sddnw.server.domain.save;

import com.sddnw.server.domain.enums.DiplomacyAction;
import com.sddnw.server.domain.enums.DiplomacyTreaty;

/**
 * Предложение, ждущее ответа человека, в слепке партии — field-backlog, пункт 182. Лежит у
 * предлагающего; адресат — местом игрока, потому что идентификаторы в другой партии другие.
 */
public record ProposalSnapshot(Integer toSlot, DiplomacyAction action, DiplomacyTreaty treaty,
                               String offeredTech, String requestedTech, Integer turn,
                               Boolean accepted) {
}
