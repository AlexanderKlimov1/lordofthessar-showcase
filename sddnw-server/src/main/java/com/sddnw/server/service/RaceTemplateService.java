package com.sddnw.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sddnw.server.domain.RaceTrait;
import com.sddnw.server.domain.entity.history.RacePlayEntity;
import com.sddnw.server.domain.entity.history.RaceTemplateEntity;
import com.sddnw.server.dto.RaceTemplateDto;
import com.sddnw.server.repository.history.RacePlayRepository;
import com.sddnw.server.repository.history.RaceTemplateRepository;
import com.sddnw.server.web.error.ApiException;
import com.sddnw.server.web.error.BadRequestException;
import com.sddnw.server.web.error.ConflictException;
import com.sddnw.server.web.error.ForbiddenException;
import com.sddnw.server.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Свои расы — field-backlog, пункты 26 и 176: собранная раса живёт в учётной записи и берётся в
 * новую партию, а не собирается заново.
 * <p>
 * <b>Сохраняется только законная раса</b> — теми же правилами, что при входе в партию
 * ({@link RaceTraitCatalog#validate}), и вместе с ценами этого мига. <b>Берётся — какая есть</b>:
 * цены двигает балансировка, и раса, законная вчера, сегодня может не пройти. Своё при этом не
 * пропадает (пункт 176: созданное игроком не отнимается): экран показывает, что подешевело, что
 * подорожало и сколько очков стало свободно, а отказ назван словами — поправить расу игрок
 * может сам. Проверку при входе в партию делает, как и всегда, {@code PlayerRoster}.
 */
@Service
public class RaceTemplateService {

    /** Сколько своих рас в записи: тринадцать готовых с запасом. */
    static final int MAX_TEMPLATES = 20;

    private final RaceTemplateRepository repository;
    private final RacePlayRepository plays;
    private final RaceTraitCatalog catalog;
    private final Messages messages;
    private final ObjectMapper mapper = new ObjectMapper();

    public RaceTemplateService(RaceTemplateRepository repository, RacePlayRepository plays,
                               RaceTraitCatalog catalog, Messages messages) {
        this.repository = repository;
        this.plays = plays;
        this.catalog = catalog;
        this.messages = messages;
    }

    @Transactional(transactionManager = "historyTransactionManager", readOnly = true)
    public List<RaceTemplateDto> list(UUID accountId) {
        List<RaceTemplateEntity> templates = repository.findAllByAccountIdOrderByNameAsc(accountId);
        Map<UUID, List<RacePlayEntity>> byTemplate = plays.findAllByTemplateIdIn(
                        templates.stream().map(RaceTemplateEntity::getId).toList()).stream()
                .collect(java.util.stream.Collectors.groupingBy(RacePlayEntity::getTemplateId));
        return templates.stream()
                .map(template -> toDto(template, byTemplate.getOrDefault(template.getId(), List.of())))
                .toList();
    }

    /**
     * Партия начата своей расой из кабинета — field-backlog, пункт 99. Если раса взята по чужой
     * ссылке, автору прибавляется сыгравший; своя раса, собранная здесь же, ничего не пишет: «вашей
     * расой сыграли» — о других людях. Своей транзакцией истории: партия живёт в другой базе.
     */
    @Transactional(transactionManager = "historyTransactionManager",
            propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void started(UUID accountId, UUID templateId, UUID gameId) {
        if (accountId == null || templateId == null) {
            return;
        }
        RaceTemplateEntity template = repository.findById(templateId).orElse(null);
        if (template == null || !accountId.equals(template.getAccountId()) || template.getOriginId() == null
                || plays.findByAccountIdAndGameId(accountId, gameId).isPresent()) {
            return;
        }
        RacePlayEntity play = new RacePlayEntity();
        play.setTemplateId(template.getOriginId());
        play.setAccountId(accountId);
        play.setGameId(gameId);
        play.setStartedAt(OffsetDateTime.now());
        plays.save(play);
    }

    /** Исход партии, начатой чужой расой (пункт 99): пишется вместе с историей партий. */
    @Transactional(transactionManager = "historyTransactionManager")
    public void finished(UUID accountId, UUID gameId, String outcome) {
        plays.findByAccountIdAndGameId(accountId, gameId).ifPresent(play -> {
            play.setOutcome(outcome);
            plays.save(play);
        });
    }

    @Transactional(transactionManager = "historyTransactionManager")
    public RaceTemplateDto save(UUID accountId, RaceTemplateDto request) {
        List<String> traits = catalog.validate(request.traits());
        RaceTemplateEntity template;
        if (request.id() == null) {
            if (repository.countByAccountId(accountId) >= MAX_TEMPLATES) {
                throw new ConflictException("raceTemplate.full", MAX_TEMPLATES);
            }
            template = new RaceTemplateEntity();
            template.setOriginId(originOf(accountId, request.originId()));
        } else {
            template = own(accountId, request.id());
        }
        Map<String, Integer> prices = new LinkedHashMap<>();
        for (String code : traits) {
            prices.put(code, catalog.require(code).picks());
        }
        template.setAccountId(accountId);
        template.setName(request.name().trim());
        try {
            template.setTraits(mapper.writeValueAsString(traits));
            template.setPrices(mapper.writeValueAsString(prices));
        } catch (Exception cause) {
            throw new BadRequestException("raceTemplate.bad");
        }
        template.setSpent(spent(traits));
        template.setBudget(catalog.picksBudget());
        template.setUpdatedAt(OffsetDateTime.now());
        RaceTemplateEntity saved = repository.saveAndFlush(template);
        return toDto(saved, plays.findAllByTemplateIdIn(List.of(saved.getId())));
    }

    /**
     * Автор расы, пришедшей по ссылке (пункт 99): чужая раса — её автор, а раса, которую уже
     * передавали дальше, — первый автор по цепочке. Своя же ссылка и несуществующая раса автора
     * не дают: засчитывать себе свои партии незачем, а выдумать автора нельзя.
     */
    private UUID originOf(UUID accountId, UUID linked) {
        if (linked == null) {
            return null;
        }
        RaceTemplateEntity source = repository.findById(linked).orElse(null);
        if (source == null) {
            return null;
        }
        UUID root = source.getOriginId() != null ? source.getOriginId() : source.getId();
        RaceTemplateEntity author = repository.findById(root).orElse(null);
        return author == null || accountId.equals(author.getAccountId()) ? null : root;
    }

    @Transactional(transactionManager = "historyTransactionManager")
    public void delete(UUID accountId, UUID templateId) {
        repository.delete(own(accountId, templateId));
    }

    /** Расы удалённой записи: без хозяина они никому не нужны. */
    @Transactional(transactionManager = "historyTransactionManager")
    public void deleteByAccount(UUID accountId) {
        repository.deleteByAccountId(accountId);
    }

    private RaceTemplateEntity own(UUID accountId, UUID templateId) {
        RaceTemplateEntity template = repository.findById(templateId)
                .orElseThrow(() -> new NotFoundException("raceTemplate.notFound"));
        if (!template.getAccountId().equals(accountId)) {
            throw new ForbiddenException("raceTemplate.notYours");
        }
        return template;
    }

    /** Потрачено набором по нынешним ценам — с надбавками связок, как считает {@code validate}. */
    private Integer spent(List<String> traits) {
        int sum = 0;
        for (String code : traits) {
            try {
                sum += catalog.require(code).picks();
            } catch (NotFoundException absent) {
                // Стороны больше нет — она ничего и не стоит; об этом скажет отказ.
            }
        }
        return sum + catalog.combinationExtra(traits);
    }

    private RaceTemplateDto toDto(RaceTemplateEntity template, List<RacePlayEntity> played) {
        List<String> traits;
        Map<String, Integer> prices;
        try {
            traits = mapper.readValue(template.getTraits(), new TypeReference<List<String>>() { });
            prices = mapper.readValue(template.getPrices(), new TypeReference<Map<String, Integer>>() { });
        } catch (Exception cause) {
            traits = List.of();
            prices = Map.of();
        }
        List<RaceTemplateDto.Trait> details = new ArrayList<>();
        for (String code : traits) {
            RaceTrait trait = null;
            try {
                trait = catalog.require(code);
            } catch (NotFoundException absent) {
                // Сторону убрали из справочника — показывается кодом и без нынешней цены.
            }
            details.add(new RaceTemplateDto.Trait(code, trait == null ? code : trait.name(), prices.get(code),
                    trait == null ? null : trait.picks()));
        }
        String refusal = null;
        try {
            catalog.validate(traits);
        } catch (ApiException refused) {
            refusal = messages.get(refused);
        }
        RaceTemplateDto.Plays stats = played.isEmpty() ? null : new RaceTemplateDto.Plays(
                (int) played.stream().map(RacePlayEntity::getAccountId).distinct().count(),
                played.size(),
                (int) played.stream().filter(play -> play.getOutcome() != null).count(),
                (int) played.stream().filter(play -> GameRecordService.WON.equals(play.getOutcome())).count());
        return new RaceTemplateDto(template.getId(), template.getName(), traits, details,
                template.getBudget() - template.getSpent(), catalog.picksBudget() - spent(traits), refusal,
                template.getOriginId(), stats);
    }
}
