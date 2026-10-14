package com.sddnw.server.service;

import com.sddnw.server.service.stub.TurnPhase;
import org.springframework.stereotype.Service;

/**
 * Срок выбора наследия ушедших — backlog-promo, пункт 34: что человек не выбрал до конца хода,
 * следующего за находкой, берётся первым по жребию. Идёт в начале хода (порядок 4), до науки:
 * выданное тут же учитывается её фазой.
 */
@Service
public class ArtifactChoicePhase implements TurnPhase {

    private final PlanetFindReward findReward;

    public ArtifactChoicePhase(PlanetFindReward findReward) {
        this.findReward = findReward;
    }

    @Override
    public Integer order() {
        return 4;
    }

    @Override
    public String name() {
        return "Наследие ушедших";
    }

    @Override
    public void apply(TurnContext context) {
        findReward.expire(context);
    }
}
