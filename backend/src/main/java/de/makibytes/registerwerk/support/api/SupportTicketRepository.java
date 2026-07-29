package de.makibytes.registerwerk.support.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, UUID> {

    List<SupportTicket> findByEntityIdOrderByCreatedAtDesc(UUID entityId);

    Page<SupportTicket> findByStatusOrderByCreatedAtDesc(SupportTicket.Status status, Pageable pageable);

    Page<SupportTicket> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
