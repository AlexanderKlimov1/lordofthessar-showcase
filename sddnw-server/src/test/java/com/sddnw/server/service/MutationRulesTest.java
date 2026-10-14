package com.sddnw.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sddnw.server.config.GameProperties;
import com.sddnw.server.domain.RaceTrait;
import com.sddnw.server.web.error.ConflictException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Эволюционная мутация — п. 7 (перенос MOO II): к расе прибавляются сильные стороны на 4 очка,
 * строй не меняется, взятое и несовместимое не предлагается. Справочник — настоящий.
 */
class MutationRulesTest {

    private final RaceTraitCatalog catalog = new RaceTraitCatalog(
            new GameProperties(8, 4, 1.5, "star-names.txt",
                    "../resources/Technologies/tech.json",
                    "../resources/Buildings/buildings.json",
                    "../resources/Races/race-traits.json",
                    "../resources/Ships/ship-components.json",
                    "../resources/Leaders/leaders.json"),
            new ObjectMapper());

    @Test
    @DisplayName("Мутация предлагает только сильные стороны, которых нет, и не трогает строй")
    void optionsArePositiveNewAndKeepGovernment() {
        List<String> owned = List.of("gov-democracy", "charismatic");
        List<RaceTrait> options = catalog.mutationOptions(owned);
        assertThat(options).isNotEmpty();
        assertThat(options).allMatch(trait -> trait.picks() > 0);
        assertThat(options).noneMatch(trait -> owned.contains(trait.code()));
        assertThat(options).noneMatch(trait -> trait.code().startsWith("gov-"));
    }

    @Test
    @DisplayName("Выбор дороже четырёх очков — отказ, в пределах — стороны прибавляются")
    void budgetIsFourPicks() {
        List<String> owned = List.of("gov-democracy");
        List<RaceTrait> options = catalog.mutationOptions(owned);
        RaceTrait cheap = options.stream().filter(trait -> trait.picks() <= RaceTraitCatalog.MUTATION_PICKS)
                .findFirst().orElseThrow();
        assertThat(catalog.mutated(owned, List.of(cheap.code()), RaceTraitCatalog.MUTATION_PICKS))
                .contains("gov-democracy", cheap.code());
        RaceTrait dear = options.stream().filter(trait -> trait.picks() > RaceTraitCatalog.MUTATION_PICKS)
                .findFirst().orElse(null);
        if (dear != null) {
            assertThatThrownBy(() -> catalog.mutated(owned, List.of(dear.code()), RaceTraitCatalog.MUTATION_PICKS))
                    .isInstanceOf(ConflictException.class);
        }
        assertThatThrownBy(() -> catalog.mutated(owned, List.of("gov-feudal"), RaceTraitCatalog.MUTATION_PICKS))
                .isInstanceOf(ConflictException.class);
    }
}
