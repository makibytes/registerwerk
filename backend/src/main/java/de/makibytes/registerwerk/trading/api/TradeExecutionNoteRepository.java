package de.makibytes.registerwerk.trading.api;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TradeExecutionNoteRepository extends JpaRepository<TradeExecutionNote, UUID> {

    List<TradeExecutionNote> findByExecutionIdOrderByCreatedAtAsc(UUID executionId);
}
