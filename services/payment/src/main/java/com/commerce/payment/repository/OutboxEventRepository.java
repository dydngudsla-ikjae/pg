package com.commerce.payment.repository;

import com.commerce.payment.domain.OutboxEvent;
import com.commerce.payment.domain.OutboxEventStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {
    List<OutboxEvent> findByStatusOrderByCreatedAtAsc(OutboxEventStatus status);
}