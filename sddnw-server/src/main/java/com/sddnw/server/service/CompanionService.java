package com.sddnw.server.service;

import com.sddnw.server.domain.entity.history.GameRecordEntity;
import com.sddnw.server.repository.history.GameRecordRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Сколько подарков за приглашение у записей — field-backlog, пункты 50 и 174 ({@link CompanionRules}). */
@Service
public class CompanionService {

    private final GameRecordRepository records;

    public CompanionService(GameRecordRepository records) {
        this.records = records;
    }

    /** Число спутников (0…3) у каждой из записей; нет строки — ноль. */
    @Transactional(value = "historyTransactionManager", readOnly = true)
    public Map<UUID, Integer> count(Collection<UUID> accountIds) {
        Set<UUID> wanted = accountIds.stream().filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, Integer> result = new HashMap<>();
        if (wanted.isEmpty()) {
            return result;
        }
        List<GameRecordEntity> invited = records.findAllByInvitedByIsNotNull().stream()
                .filter(row -> wanted.contains(row.getAccountId()) || wanted.contains(row.getInvitedBy()))
                .toList();
        if (invited.isEmpty()) {
            return result;
        }
        List<GameRecordEntity> games = records.findAllByGameIdIn(
                invited.stream().map(GameRecordEntity::getGameId).collect(Collectors.toSet()));
        CompanionRules.companions(invited, games).forEach((account, partners) -> {
            if (wanted.contains(account)) {
                result.put(account, partners.size());
            }
        });
        return result;
    }
}
