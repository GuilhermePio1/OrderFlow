package com.orderflow.order.domain.model;

import com.orderflow.order.domain.event.*;
import com.orderflow.order.domain.exception.DuplicateOrderItemException;
import com.orderflow.order.domain.exception.EmptyOrderException;
import com.orderflow.order.domain.exception.InvalidOrderStateTransitionException;
import com.orderflow.order.domain.model.valueobject.*;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

/**
 * Aggregate root do contexto Ordering. Event-sourced: o estado não é
 * persistido diretamente; é derivado do stream de eventos. Comandos
 * validam invariantes, produzem eventos de domínio e aplicam-nos
 * localmente, ficando os eventos pendentes ate {@link #pullUncommittedEvents()}.
 *
 * Invariantes:
 *  - Pelo menos um item;
 *  - Itens únicos por ProductId;
 *  - Total = soma dos subtotais (calculado, não armazenado);
 *  - Transições de estado obedecem à máquina explicita em {@link OrderStatus}.
 */
public final class Order {

    private static final int CURRENT_SCHEMA_VERSION = 1;

    private OrderId id;
    private CustomerId customerId;
    private final List<OrderItem> items = new ArrayList<>();
    private Address shippingAddress;
    private OrderStatus status;
    private boolean paymentConfirmed;
    private boolean inventoryReserved;
    private long version;

    private final List<OrderEvent> uncommittedEvents = new ArrayList<>();
    private final Clock clock;

    private Order(Clock clock) {
        this.clock = clock;
    }

    // ---------- factories ----------

    /**
     * Cria um novo pedido emitindo OrderPlaced. Ponto de entrada do agregado.
     */
    public static Order place(
            OrderId orderId,
            CustomerId customerId,
            List<OrderItem> items,
            Address shippingAddress,
            Clock clock
    ) {
        Objects.requireNonNull(orderId, "orderId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(items, "items");
        Objects.requireNonNull(shippingAddress, "shippingAddress");
        Objects.requireNonNull(clock, "clock");

        if (items.isEmpty()) {
            throw new EmptyOrderException();
        }
        validateUniqueProducts(items);

        Money total = computeTotal(items);

        Order order = new Order(clock);
        OrderPlaced event = new OrderPlaced(
                UUID.randomUUID(),
                orderId,
                customerId,
                items,
                total,
                shippingAddress,
                Instant.now(clock),
                CURRENT_SCHEMA_VERSION
        );
        order.recordAndApply(event);
        return order;
    }

    /**
     * Reidrata o agregado a partir do histórico de eventos. Não gera eventos
     * pendentes; apenas reconstrói o estado.
     */
    public static Order loadFromHistory(List<OrderEvent> history, Clock clock) {
        Objects.requireNonNull(history, "history");
        Objects.requireNonNull(clock, "clock");
        if (history.isEmpty()) {
            throw new IllegalArgumentException("Cannot load order from empty history");
        }
        Order order = new Order(clock);
        for (OrderEvent event : history) {
            order.apply(event);
            order.version++;
        }
        return order;
    }

    // ---------- commands ----------

    public void confirmPayment(UUID paymentId) {
        Objects.requireNonNull(paymentId, "paymentId");
        if (paymentConfirmed) {
            return; //idempotente
        }
        if (status != OrderStatus.PLACED && status != OrderStatus.INVENTORY_RESERVED) {
            throw new InvalidOrderStateTransitionException(status, "confirmPayment");
        }
        recordAndApply(new OrderPaymentConfirmed(
                UUID.randomUUID(),
                id,
                paymentId,
                Instant.now(clock),
                CURRENT_SCHEMA_VERSION
        ));
        emitOrderConfirmedIfReady();
    }

    public void reserveInventory(UUID reservationId) {
        Objects.requireNonNull(reservationId, "reservationId");
        if (inventoryReserved) {
            return; //idempotente
        }
        if (status != OrderStatus.PLACED && status != OrderStatus.PAID) {
            throw new InvalidOrderStateTransitionException(status, "reserveInventory");
        }
        recordAndApply(new OrderInventoryReserved(
                UUID.randomUUID(),
                id,
                reservationId,
                Instant.now(clock),
                CURRENT_SCHEMA_VERSION
        ));
        emitOrderConfirmedIfReady();
    }

    public void ship(TrackingNumber trackingNumber, String carrier) {
        Objects.requireNonNull(trackingNumber, "trackingNumber");
        if (carrier == null || carrier.isBlank()) {
            throw new IllegalArgumentException("carrier must not be blank");
        }
        if (status != OrderStatus.CONFIRMED) {
            throw new InvalidOrderStateTransitionException(status, "ship");
        }
        recordAndApply(new OrderShipped(
                UUID.randomUUID(),
                id,
                trackingNumber,
                carrier,
                Instant.now(clock),
                CURRENT_SCHEMA_VERSION
        ));
    }

    public void deliver() {
        if (status != OrderStatus.SHIPPED) {
            throw new InvalidOrderStateTransitionException(status, "deliver");
        }
        recordAndApply(new OrderDelivered(
                UUID.randomUUID(),
                id,
                Instant.now(clock),
                CURRENT_SCHEMA_VERSION
        ));
    }

    public void cancel(OrderCancelled.CancellationReason reason, String details) {
        Objects.requireNonNull(reason, "reason");
        if (status.isTerminal()) {
            throw new InvalidOrderStateTransitionException(status, "cancel");
        }
        recordAndApply(new OrderCancelled(
                UUID.randomUUID(),
                id,
                reason,
                details,
                Instant.now(clock),
                CURRENT_SCHEMA_VERSION
        ));
    }

    private void emitOrderConfirmedIfReady() {
        if (paymentConfirmed && inventoryReserved && status != OrderStatus.CONFIRMED) {
            recordAndApply(new OrderConfirmed(
                    UUID.randomUUID(),
                    id,
                    Instant.now(clock),
                    CURRENT_SCHEMA_VERSION
            ));
        }
    }

    // ---------- event sourcing plumbing ----------

    private void recordAndApply(OrderEvent event) {
        apply(event);
        uncommittedEvents.add(event);
        version++;
    }

    private void apply(OrderEvent event) {
        switch (event) {
            case OrderPlaced e -> {
                this.id = e.orderId();
                this.customerId = e.customerId();
                this.items.addAll(e.items());
                this.shippingAddress = e.shippingAddress();
                this.status = OrderStatus.PLACED;
            }
            case OrderPaymentConfirmed ignored -> {
                this.paymentConfirmed = true;
                if (this.status == OrderStatus.PLACED) {
                    this.status = OrderStatus.PAID;
                }
            }
            case OrderInventoryReserved ignored -> {
                this.inventoryReserved = true;
                if (this.status == OrderStatus.PLACED) {
                    this.status = OrderStatus.INVENTORY_RESERVED;
                }
            }
            case OrderConfirmed ignored -> this.status = OrderStatus.CONFIRMED;
            case OrderShipped ignored -> this.status = OrderStatus.SHIPPED;
            case OrderDelivered ignored -> this.status = OrderStatus.DELIVERED;
            case OrderCancelled ignored -> this.status = OrderStatus.CANCELLED;
        }
    }

    /**
     * Retorna e limpa os eventos pendentes. Chamado pelo repositório
     * após persistir os eventos no event store/outbox.
     */
    public List<OrderEvent> pullUncommittedEvents() {
        List<OrderEvent> snapshot = List.copyOf(uncommittedEvents);
        uncommittedEvents.clear();
        return snapshot;
    }

    public boolean hasUncommittedEvents() {
        return !uncommittedEvents.isEmpty();
    }

    // ---------- queries ----------

    public OrderId id() {
        return id;
    }

    public CustomerId customerId() {
        return customerId;
    }

    public List<OrderItem> items() {
        return Collections.unmodifiableList(items);
    }

    public Address shippingAddress() {
        return shippingAddress;
    }

    public OrderStatus status() {
        return status;
    }

    public boolean isPaymentConfirmed() {
        return paymentConfirmed;
    }

    public boolean isInventoryReserved() {
        return inventoryReserved;
    }

    /**
     * Numero do último evento aplicado. Base da concorrência otimista
     * no event store.
     */
    public long version() {
        return version;
    }

    public Money totalAmount() {
        return computeTotal(items);
    }

    // ---------- helpers ----------

    private static Money computeTotal(List<OrderItem> items) {
        Money total = items.getFirst().subtotal();
        for (int i = 1; i < items.size(); i++) {
            total = total.plus(items.get(i).subtotal());
        }
        return total;
    }

    private static void validateUniqueProducts(List<OrderItem> items) {
        Set<ProductId> seen = new HashSet<>();
        for (OrderItem item : items) {
            if (!seen.add(item.productId())) {
                throw new DuplicateOrderItemException(item.productId());
            }
        }
    }
}
