package com.orderflow.order.application;

import com.orderflow.order.domain.event.OrderEvent;
import com.orderflow.order.domain.exception.ConcurrencyConflictException;
import com.orderflow.order.domain.exception.OrderNotFoundException;
import com.orderflow.order.domain.model.Order;
import com.orderflow.order.domain.model.valueobject.OrderId;
import com.orderflow.order.domain.repository.OrderRepository;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fake in-memory do {@link OrderRepository} para testes de use case. Modela
 * o stream de eventos por agregado e a concorrência otimista, sem depender
 * de Postgres/R2DBC.
 */
public final class InMemoryOrderRepository implements OrderRepository {

    private final Map<OrderId, List<OrderEvent>> streams = new HashMap<>();
    private final Clock clock;
    private final AtomicInteger conflictsToInject = new AtomicInteger();
    private final AtomicInteger saveCount = new AtomicInteger();

    public InMemoryOrderRepository(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Mono<Order> findById(OrderId orderId) {
        return Mono.defer(() -> {
            List<OrderEvent> history = streams.get(orderId);
            if (history == null || history.isEmpty()) {
                return Mono.error(new OrderNotFoundException(orderId));
            }
            return Mono.fromCallable(() -> Order.loadFromHistory(List.copyOf(history), clock));
        });
    }

    @Override
    public Mono<Void> save(Order order, long expectedVersion) {
        return Mono.defer(() -> {
            saveCount.incrementAndGet();
            if (conflictsToInject.getAndUpdate(c -> Math.max(0, c - 1)) > 0) {
                return Mono.error(new ConcurrencyConflictException(
                        order.id(), expectedVersion, expectedVersion + 1));
            }
            List<OrderEvent> stream = streams.computeIfAbsent(order.id(), k -> new ArrayList<>());
            if (stream.size() != expectedVersion) {
                return Mono.error(new ConcurrencyConflictException(
                        order.id(), expectedVersion, stream.size()));
            }
            stream.addAll(order.pullUncommittedEvents());
            return Mono.empty();
        });
    }

    public void seed(Order order) {
        streams.put(order.id(), new ArrayList<>(order.pullUncommittedEvents()));
    }

    public List<OrderEvent> events(OrderId orderId) {
        return Collections.unmodifiableList(streams.getOrDefault(orderId, List.of()));
    }

    public void injectConcurrencyConflicts(int n) {
        conflictsToInject.set(n);
    }

    public int saveCount() {
        return saveCount.get();
    }
}
