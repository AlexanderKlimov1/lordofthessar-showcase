package com.sddnw.server.service;

import com.sddnw.server.domain.RaceTrait;
import com.sddnw.server.domain.entity.PlayerEntity;
import com.sddnw.server.domain.enums.PlayerType;
import com.sddnw.server.repository.PlayerRepository;
import com.sddnw.server.web.error.ConflictException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Эволюционная мутация — п. 7 (13.10.2026, перенос MOO II): «when evolutionary mutation is
 * researched, you may choose 4 Picks worth of racial specials to add to your characteristics».
 * <p>
 * Человеку очки откладываются ({@code player.mutation_picks}) и тратятся окном выбора; не
 * потраченное сгорает при ответе. ИИ выбирает сразу: самые дорогие стороны, что влезают, при равной
 * цене — по порядку справочника. Жребия нет — партия обязана повторяться.
 */
@Service
public class MutationService {

    /** Технология мутации — Biology. */
    public static final String TECH = "evolutionary-mutation";

    private final RaceTraitCatalog catalog;
    private final PlayerRepository playerRepository;

    public MutationService(RaceTraitCatalog catalog, PlayerRepository playerRepository) {
        this.catalog = catalog;
        this.playerRepository = playerRepository;
    }

    /** Изученное прибавилось — ИИ мутирует сразу, человеку откладываются очки. */
    public void afterResearch(PlayerEntity player, Collection<String> acquired) {
        if (!acquired.contains(TECH)) {
            return;
        }
        if (player.getPlayerType() != PlayerType.HUMAN) {
            player.setRaceTraitCodes(catalog.mutated(player.getRaceTraitCodes(), autoPick(player), RaceTraitCatalog.MUTATION_PICKS));
            return;
        }
        player.setMutationPicks(RaceTraitCatalog.MUTATION_PICKS);
    }

    /** Выбор ИИ: самые дорогие стороны, что влезают в очки, группа-переключатель — однажды. */
    List<String> autoPick(PlayerEntity player) {
        List<RaceTrait> options = new ArrayList<>(catalog.mutationOptions(player.getRaceTraitCodes()));
        options.sort(Comparator.comparing(RaceTrait::picks).reversed());
        List<String> chosen = new ArrayList<>();
        int left = RaceTraitCatalog.MUTATION_PICKS;
        for (RaceTrait trait : options) {
            if (trait.picks() > left) {
                continue;
            }
            List<String> attempt = new ArrayList<>(chosen);
            attempt.add(trait.code());
            try {
                catalog.mutated(player.getRaceTraitCodes(), attempt, RaceTraitCatalog.MUTATION_PICKS);
            } catch (ConflictException incompatible) {
                continue;
            }
            chosen = attempt;
            left -= trait.picks();
        }
        return chosen;
    }

    /** Что игрок может взять сейчас и сколько у него очков. */
    @Transactional(readOnly = true)
    public Map<String, Object> options(PlayerEntity player) {
        return Map.of("picks", player.getMutationPicks(),
                "options", player.getMutationPicks() <= 0 ? List.of() : catalog.mutationOptions(player.getRaceTraitCodes())
                        .stream()
                        .map(trait -> Map.of("code", trait.code(), "name", trait.name(),
                                "description", trait.description(), "picks", trait.picks(),
                                "group", trait.groupCode()))
                        .toList());
    }

    /** Ответ игрока: выбранные стороны прибавляются, оставшиеся очки сгорают. */
    @Transactional
    public void choose(UUID playerId, List<String> traits) {
        PlayerEntity player = playerRepository.findById(playerId).orElseThrow();
        if (player.getMutationPicks() <= 0) {
            throw new ConflictException("race.mutation.none");
        }
        player.setRaceTraitCodes(catalog.mutated(player.getRaceTraitCodes(),
                traits == null ? List.of() : traits, player.getMutationPicks()));
        player.setMutationPicks(0);
        playerRepository.save(player);
    }
}
