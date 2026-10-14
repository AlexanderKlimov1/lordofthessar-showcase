package com.sddnw.server.repository.history;

import com.sddnw.server.domain.entity.history.RefusalCountEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Счёт отказов по ключу — field-backlog, пункт 77. */
public interface RefusalCountRepository extends JpaRepository<RefusalCountEntity, String> {

    /** Сначала частые: верх списка — работа для пункта 76 «ограничения вместо отказов». */
    List<RefusalCountEntity> findAllByOrderByRefusalsDescMessageKeyAsc();
}
