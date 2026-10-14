package com.sddnw.server.service;

import com.sddnw.server.service.stub.TurnPhase;
import org.springframework.stereotype.Service;

/**
 * Дипломатия империй ИИ в конце хода — п. 15.
 * <p>
 * Соседи приходят сами: объявляют войну, просят мира, предлагают договоры, требуют дань и
 * делают подарки. Что именно — решает характер правителя; само решение в
 * {@link AiDiplomacyService}, фаза только называет момент.
 * <p>
 * Идёт <b>последней</b> (порядок 17), после знакомства ({@link ContactPhase}, 15):
 * разговаривать можно лишь с тем, с кем уже знаком, и сосед, встреченный на этом же ходу,
 * должен успеть быть услышанным. Галактические события (16) к дипломатии отношения не
 * имеют, и порядок с ними безразличен.
 */
@Service
public class AiDiplomacyPhase implements TurnPhase {

    private final AiDiplomacyService aiDiplomacyService;
    private final DiplomacyService diplomacyService;

    public AiDiplomacyPhase(AiDiplomacyService aiDiplomacyService, DiplomacyService diplomacyService) {
        this.aiDiplomacyService = aiDiplomacyService;
        this.diplomacyService = diplomacyService;
    }

    @Override
    public Integer order() {
        return 17;
    }

    @Override
    public String name() {
        return "Дипломатия ИИ";
    }

    @Override
    public void apply(TurnContext context) {
        /*
          Сперва итог предложений людям (field-backlog, пункт 182) — и в любой партии, даже без
          действующего ИИ: это ответ человека, а не решение соседа. Своей фазой это не сделано
          нарочно: порядок у фаз целый, и вторая фаза с тем же номером шла бы в порядке бинов. А
          итог ДО новых предложений ИИ: то, что предложено на этом ходу, ждёт ответа до следующего.
        */
        diplomacyService.resolveProposals(context);
        // Партия без действующего ИИ (трек техдолга, пункт 17): соседи молчат.
        if (!Boolean.TRUE.equals(context.game().getAiActive())) {
            return;
        }
        aiDiplomacyService.act(context);
    }
}
