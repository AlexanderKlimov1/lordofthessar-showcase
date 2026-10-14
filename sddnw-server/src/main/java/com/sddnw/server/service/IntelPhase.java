package com.sddnw.server.service;

import com.sddnw.server.service.stub.TurnPhase;
import org.springframework.stereotype.Service;

/**
 * Память о чужих колониях — п. 15, туман войны ({@link IntelService}).
 * <p>
 * Последняя фаза хода: память снимается с того, что получилось после боёв, высадок,
 * бомбёжек и событий, — именно это игрок и увидит, открыв карту, пока в системе у него
 * глаза, и именно это будет помнить, когда их там не станет.
 */
@Service
public class IntelPhase implements TurnPhase {

    private final IntelService intelService;

    public IntelPhase(IntelService intelService) {
        this.intelService = intelService;
    }

    @Override
    public Integer order() {
        return 20;
    }

    @Override
    public String name() {
        return "Память о чужих колониях";
    }

    @Override
    public void apply(TurnContext context) {
        intelService.refresh(context);
    }
}
