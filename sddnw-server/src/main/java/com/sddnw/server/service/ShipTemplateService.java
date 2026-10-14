package com.sddnw.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sddnw.server.domain.ShipComponent;
import com.sddnw.server.domain.ShipHull;
import com.sddnw.server.domain.entity.history.ShipTemplateEntity;
import com.sddnw.server.dto.SaveShipDesignRequest;
import com.sddnw.server.dto.ShipTemplateDto;
import com.sddnw.server.repository.history.ShipTemplateRepository;
import com.sddnw.server.web.error.BadRequestException;
import com.sddnw.server.web.error.ConflictException;
import com.sddnw.server.web.error.ForbiddenException;
import com.sddnw.server.web.error.NotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Библиотека проектов кораблей — field-backlog, пункт 96, по образцу шаблонов стройки
 * ({@link BuildTemplateService}): принадлежит учётной записи, чужое не правится и не отдаётся.
 * <p>
 * <b>Состав сервер здесь не проверяет</b>, как и шаблоны стройки: библиотека живёт вне партии, где
 * ни изученного, ни корпуса ещё нет. Проверяет его окно дизайна, взяв проект в партию, — тем же
 * правилом, что любой проект ({@code ShipDesignService.save}: место, носимые модификации,
 * изученное), а чего справочник не знает или что ещё не изучено, окно называет словами.
 */
@Service
public class ShipTemplateService {

    /** Сколько проектов в библиотеке — шесть ячеек партии с запасом на разные эпохи. */
    static final int MAX_TEMPLATES = 30;

    private final ShipTemplateRepository repository;
    private final ShipCatalog shipCatalog;
    private final ObjectMapper mapper = new ObjectMapper();

    public ShipTemplateService(ShipTemplateRepository repository, ShipCatalog shipCatalog) {
        this.repository = repository;
        this.shipCatalog = shipCatalog;
    }

    @Transactional(transactionManager = "historyTransactionManager", readOnly = true)
    public List<ShipTemplateDto> list(UUID accountId) {
        return repository.findAllByAccountIdOrderByNameAsc(accountId).stream().map(this::toDto).toList();
    }

    @Transactional(transactionManager = "historyTransactionManager")
    public ShipTemplateDto save(UUID accountId, ShipTemplateDto request) {
        ShipTemplateEntity template;
        if (request.id() == null) {
            if (repository.countByAccountId(accountId) >= MAX_TEMPLATES) {
                throw new ConflictException("shipTemplate.full", MAX_TEMPLATES);
            }
            template = new ShipTemplateEntity();
        } else {
            template = own(accountId, request.id());
        }
        template.setAccountId(accountId);
        template.setName(request.name().trim());
        template.setHullCode(request.hullCode());
        try {
            template.setComponents(mapper.writeValueAsString(request.components()));
        } catch (Exception cause) {
            throw new BadRequestException("shipTemplate.bad");
        }
        template.setSpecs(specsOf(request.hullCode(), request.components()));
        template.setUpdatedAt(OffsetDateTime.now());
        return toDto(repository.saveAndFlush(template));
    }

    @Transactional(transactionManager = "historyTransactionManager")
    public void delete(UUID accountId, UUID templateId) {
        repository.delete(own(accountId, templateId));
    }

    /** Библиотека удалённой записи: без хозяина она никому не нужна. */
    @Transactional(transactionManager = "historyTransactionManager")
    public void deleteByAccount(UUID accountId) {
        repository.deleteByAccountId(accountId);
    }

    private ShipTemplateEntity own(UUID accountId, UUID templateId) {
        ShipTemplateEntity template = repository.findById(templateId)
                .orElseThrow(() -> new NotFoundException("shipTemplate.notFound"));
        if (!template.getAccountId().equals(accountId)) {
            throw new ForbiddenException("shipTemplate.notYours");
        }
        return template;
    }

    private ShipTemplateDto toDto(ShipTemplateEntity template) {
        List<SaveShipDesignRequest.ShipComponentChoice> components;
        try {
            components = mapper.readValue(template.getComponents(),
                    new TypeReference<List<SaveShipDesignRequest.ShipComponentChoice>>() { });
        } catch (Exception cause) {
            components = List.of();
        }
        List<String> parts = components.stream()
                .map(part -> known(part.code(), false) + (part.count() != null && part.count() > 1 ? " ×" + part.count() : ""))
                .toList();
        return new ShipTemplateDto(template.getId(), template.getName(), template.getHullCode(), components,
                changed(template, components), known(template.getHullCode(), true), parts);
    }

    /**
     * Снимок справочника на миг сохранения — field-backlog, пункт 176: корпус и каждый компонент
     * одной строкой чисел (место, цена, технология, действие), без названий и описаний — перевод
     * не меняет корабля. По снимку библиотека видит, что балансировка тронула части проекта, и
     * подсвечивает его, а не молча отдаёт другой корабль под тем же именем.
     */
    private String specsOf(String hullCode, List<SaveShipDesignRequest.ShipComponentChoice> components) {
        java.util.Map<String, String> specs = new java.util.TreeMap<>();
        fingerprint(hullCode, true).ifPresent(spec -> specs.put(hullCode, spec));
        for (SaveShipDesignRequest.ShipComponentChoice part : components) {
            fingerprint(part.code(), false).ifPresent(spec -> specs.put(part.code(), spec));
        }
        try {
            return mapper.writeValueAsString(specs);
        } catch (Exception cause) {
            return null;
        }
    }

    /** Что изменилось в справочнике с сохранения — названиями на языке читателя; пусто — ничего или снимка нет. */
    private List<String> changed(ShipTemplateEntity template, List<SaveShipDesignRequest.ShipComponentChoice> components) {
        if (template.getSpecs() == null) {
            // Сохранено до снимков (пункт 176): сравнивать не с чем, и выдумывать перемену нельзя.
            return List.of();
        }
        java.util.Map<String, String> saved;
        try {
            saved = mapper.readValue(template.getSpecs(), new TypeReference<java.util.Map<String, String>>() { });
        } catch (Exception cause) {
            return List.of();
        }
        List<String> result = new java.util.ArrayList<>();
        java.util.Set<String> codes = new java.util.LinkedHashSet<>();
        codes.add(template.getHullCode());
        components.forEach(part -> codes.add(part.code()));
        for (String code : codes) {
            boolean hull = code.equals(template.getHullCode());
            java.util.Optional<String> now = fingerprint(code, hull);
            if (saved.containsKey(code) && !now.equals(java.util.Optional.of(saved.get(code)))) {
                result.add(now.isPresent() ? nameOf(code, hull) : code);
            }
        }
        return result;
    }

    private java.util.Optional<String> fingerprint(String code, boolean hull) {
        try {
            if (hull) {
                ShipHull one = shipCatalog.hull(code);
                return java.util.Optional.of(String.join("|", String.valueOf(one.space()), String.valueOf(one.cost()),
                        String.valueOf(one.structure()), String.valueOf(one.command()),
                        String.valueOf(one.hitChancePercent()), String.valueOf(one.systemFactor()),
                        String.valueOf(one.requiredTechCode())));
            }
            ShipComponent one = shipCatalog.component(code);
            return java.util.Optional.of(String.join("|", String.valueOf(one.slot()), String.valueOf(one.space()),
                    String.valueOf(one.cost()), String.valueOf(one.requiredTechCode()),
                    String.valueOf(new java.util.TreeMap<>(one.effects() == null ? java.util.Map.of() : one.effects())),
                    String.valueOf(one.weaponKind()), String.valueOf(one.envelops()),
                    String.valueOf(one.modifications())));
        } catch (NotFoundException absent) {
            return java.util.Optional.empty();
        }
    }

    /** Название части на языке читателя; справочник её больше не знает — код. */
    private String known(String code, boolean hull) {
        try {
            return nameOf(code, hull);
        } catch (NotFoundException absent) {
            return code;
        }
    }

    private String nameOf(String code, boolean hull) {
        return hull ? shipCatalog.hull(code).name() : shipCatalog.component(code).name();
    }
}
