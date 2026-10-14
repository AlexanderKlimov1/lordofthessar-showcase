package com.sddnw.server.repository.history;

import com.sddnw.server.domain.entity.history.ServerSecretEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/** Ключи сервера — field-backlog, пункт 22. */
public interface ServerSecretRepository extends JpaRepository<ServerSecretEntity, String> {
}
