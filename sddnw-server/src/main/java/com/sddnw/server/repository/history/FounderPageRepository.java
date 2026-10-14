package com.sddnw.server.repository.history;

import com.sddnw.server.domain.entity.history.FounderPageEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** Страница пакета основателя — одна строка на сервер. */
public interface FounderPageRepository extends JpaRepository<FounderPageEntity, String> {
}
