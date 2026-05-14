package com.orderflow.order.domain.model;

import com.orderflow.order.domain.event.*;
import com.orderflow.order.domain.exception.DuplicateOrderItemException;
import com.orderflow.order.domain.exception.EmptyOrderException;
import com.orderflow.order.domain.exception.InvalidOrderStateTransitionException;
import com.orderflow.order.domain.model.valueobject.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Order — agregado event-sourced")
class OrderTest {

    private static final Instant FIXED_NOW = Instant.parse("2025-01-15T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private static final Address ADDRESS = new Address(
            "Rua das Flores", "100", "Apto 12", "Centro",
            "São Paulo", "SP", "01000-000", "BR"
    );

    private static OrderItem item(String price, int qty) {
        return new OrderItem(
                ProductId.of(UUID.randomUUID()),
                Quantity.of(qty),
                Money.of(price, "BRL")
        );
    }

    private static Order placedOrder() {
        return Order.place(
                OrderId.generate(),
                CustomerId.of(UUID.randomUUID()),
                List.of(item("10.00", 2), item("5.00", 1)),
                ADDRESS,
                CLOCK
        );
    }

    @Nested
    @DisplayName("place (factory)")
    class Place {

        @Test
        @DisplayName("rejeita orderId nulo")
        void rejectsNullOrderId() {
            assertThatNullPointerException()
                    .isThrownBy(() -> Order.place(null, CustomerId.of(UUID.randomUUID()),
                            List.of(item("1.00", 1)), ADDRESS, CLOCK))
                    .withMessageContaining("orderId");
        }

        @Test
        @DisplayName("rejeita customerId nulo")
        void rejectsNullCustomerId() {
            assertThatNullPointerException()
                    .isThrownBy(() -> Order.place(OrderId.generate(), null,
                            List.of(item("1.00", 1)), ADDRESS, CLOCK))
                    .withMessageContaining("customerId");
        }

        @Test
        @DisplayName("rejeita items nulo")
        void rejectsNullItems() {
            assertThatNullPointerException()
                    .isThrownBy(() -> Order.place(OrderId.generate(), CustomerId.of(UUID.randomUUID()),
                            null, ADDRESS, CLOCK))
                    .withMessageContaining("items");
        }

        @Test
        @DisplayName("rejeita shippingAddress nulo")
        void rejectsNullShippingAddress() {
            assertThatNullPointerException()
                    .isThrownBy(() -> Order.place(OrderId.generate(), CustomerId.of(UUID.randomUUID()),
                            List.of(item("1.00", 1)), null, CLOCK))
                    .withMessageContaining("shippingAddress");
        }

        @Test
        @DisplayName("rejeita clock nulo")
        void rejectsNullClock() {
            assertThatNullPointerException()
                    .isThrownBy(() -> Order.place(OrderId.generate(), CustomerId.of(UUID.randomUUID()),
                            List.of(item("1.00", 1)), ADDRESS, null))
                    .withMessageContaining("clock");
        }

        @Test
        @DisplayName("rejeita lista vazia de itens")
        void rejectsEmptyItems() {
            assertThatThrownBy(() -> Order.place(OrderId.generate(), CustomerId.of(UUID.randomUUID()),
                    List.of(), ADDRESS, CLOCK))
                    .isInstanceOf(EmptyOrderException.class);
        }

        @Test
        @DisplayName("rejeita itens duplicados pelo mesmo ProductId")
        void rejectsDuplicateProducts() {
            ProductId productId = ProductId.of(UUID.randomUUID());
            OrderItem a = new OrderItem(productId, Quantity.of(1), Money.of("10.00", "BRL"));
            OrderItem b = new OrderItem(productId, Quantity.of(2), Money.of("10.00", "BRL"));

            assertThatThrownBy(() -> Order.place(OrderId.generate(), CustomerId.of(UUID.randomUUID()),
                    List.of(a, b), ADDRESS, CLOCK))
                    .isInstanceOf(DuplicateOrderItemException.class)
                    .hasMessageContaining(productId.toString());
        }

        @Test
        @DisplayName("status inicial é PLACED")
        void initialStatusIsPlaced() {
            assertThat(placedOrder().status()).isEqualTo(OrderStatus.PLACED);
        }

        @Test
        @DisplayName("totalAmount é a soma dos subtotais dos itens")
        void totalAmountIsSumOfSubtotals() {
            Order order = Order.place(
                    OrderId.generate(),
                    CustomerId.of(UUID.randomUUID()),
                    List.of(item("10.00", 2), item("5.00", 3), item("1.50", 4)),
                    ADDRESS,
                    CLOCK
            );

            // 20.00 + 15.00 + 6.00 = 41.00
            assertThat(order.totalAmount().amount()).isEqualByComparingTo("41.00");
        }

        @Test
        @DisplayName("emite OrderPlaced com payload completo, ocorrido em Instant.now(clock)")
        void emitsOrderPlacedWithFullPayload() {
            OrderId orderId = OrderId.generate();
            CustomerId customerId = CustomerId.of(UUID.randomUUID());
            List<OrderItem> items = List.of(item("10.00", 2));

            Order order = Order.place(orderId, customerId, items, ADDRESS, CLOCK);

            List<OrderEvent> events = order.pullUncommittedEvents();
            assertThat(events).hasSize(1);
            assertThat(events.getFirst()).isInstanceOfSatisfying(OrderPlaced.class, placed ->{
                assertThat(placed.orderId()).isEqualTo(orderId);
                assertThat(placed.customerId()).isEqualTo(customerId);
                assertThat(placed.items()).containsExactlyElementsOf(items);
                assertThat(placed.totalAmount().amount()).isEqualByComparingTo("20.00");
                assertThat(placed.shippingAddress()).isEqualTo(ADDRESS);
                assertThat(placed.occurredAt()).isEqualTo(FIXED_NOW);
                assertThat(placed.schemaVersion()).isEqualTo(1);
                assertThat(placed.eventId()).isNotNull();
            });
        }

        @Test
        @DisplayName("version inicial é 1 (após o OrderPlaced)")
        void versionStartsAtOne() {
            assertThat(placedOrder().version()).isEqualTo(1L);
        }

        @Test
        @DisplayName("items() é uma view não-modificável")
        void itemsViewIsUnmodifiable() {
            Order order = placedOrder();
            assertThatThrownBy(() -> order.items().add(item("1.00", 1)))
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("confirmPayment")
    class ConfirmPayment {

        @Test
        @DisplayName("a partir de PLACED, transiciona para PAID e emite OrderPaymentConfirmed")
        void fromPlacedTransitionsToPaid() {
            Order order = placedOrder();
            order.pullUncommittedEvents();

            UUID paymentId = UUID.randomUUID();
            order.confirmPayment(paymentId);

            assertThat(order.status()).isEqualTo(OrderStatus.PAID);
            assertThat(order.isPaymentConfirmed()).isTrue();
            List<OrderEvent> events = order.pullUncommittedEvents();
            assertThat(events).hasSize(1);
            assertThat(events.getFirst()).isInstanceOfSatisfying(OrderPaymentConfirmed.class, e -> {
                assertThat(e.paymentId()).isEqualTo(paymentId);
                assertThat(e.orderId()).isEqualTo(order.id());
                assertThat(e.occurredAt()).isEqualTo(FIXED_NOW);
            });
        }

        @Test
        @DisplayName("a partir de INVENTORY_RESERVED, emite OrderConfirmed adicional e vai para CONFIRMED")
        void fromInventoryReservedTransitionsToConfirmed() {
            Order order = placedOrder();
            order.reserveInventory(UUID.randomUUID());
            order.pullUncommittedEvents();

            order.confirmPayment(UUID.randomUUID());

            assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(order.isPaymentConfirmed()).isTrue();
            assertThat(order.isInventoryReserved()).isTrue();

            List<OrderEvent> events = order.pullUncommittedEvents();
            assertThat(events).hasSize(2);
            assertThat(events.getFirst()).isInstanceOf(OrderPaymentConfirmed.class);
            assertThat(events.get(1)).isInstanceOf(OrderConfirmed.class);
        }

        @Test
        @DisplayName("é idempotente quando o pagamento já foi confirmado")
        void isIdempotent() {
            Order order = placedOrder();
            order.confirmPayment(UUID.randomUUID());
            order.pullUncommittedEvents();

            order.confirmPayment(UUID.randomUUID());

            assertThat(order.pullUncommittedEvents()).isEmpty();
            assertThat(order.status()).isEqualTo(OrderStatus.PAID);
        }

        @Test
        @DisplayName("rejeita paymentId nulo")
        void rejectsNullPaymentId() {
            Order order = placedOrder();
            assertThatNullPointerException().isThrownBy(() -> order.confirmPayment(null));
        }

        @Test
        @DisplayName("rejeita quando status não é PLACED nem INVENTORY_RESERVED (e.g. CANCELLED)")
        void rejectsFromInvalidStatus() {
            Order order = placedOrder();
            order.cancel(OrderCancelled.CancellationReason.CUSTOMER_REQUESTED, null);

            assertThatThrownBy(() -> order.confirmPayment(UUID.randomUUID()))
                    .isInstanceOf(InvalidOrderStateTransitionException.class)
                    .hasMessageContaining("confirmPayment")
                    .hasMessageContaining("CANCELLED");
        }
    }

    @Nested
    @DisplayName("reserveInventory")
    class ReserveInventory {

        @Test
        @DisplayName("a partir de PLACED, vai para INVENTORY_RESERVED")
        void fromPlacedTransitionsToInventoryReserved() {
            Order order = placedOrder();
            order.pullUncommittedEvents();

            UUID reservationId = UUID.randomUUID();
            order.reserveInventory(reservationId);

            assertThat(order.status()).isEqualTo(OrderStatus.INVENTORY_RESERVED);
            assertThat(order.isInventoryReserved()).isTrue();
            List<OrderEvent> events = order.pullUncommittedEvents();
            assertThat(events).hasSize(1);
            assertThat(events.getFirst()).isInstanceOfSatisfying(OrderInventoryReserved.class, e -> {
                assertThat(e.reservationId()).isEqualTo(reservationId);
                assertThat(e.orderId()).isEqualTo(order.id());
            });
        }

        @Test
        @DisplayName("a partir de PAID, emite OrderConfirmed e vai para CONFIRMED")
        void fromPaidTransitionsToConfirmed() {
            Order order = placedOrder();
            order.confirmPayment(UUID.randomUUID());
            order.pullUncommittedEvents();

            order.reserveInventory(UUID.randomUUID());

            assertThat(order.status()).isEqualTo(OrderStatus.CONFIRMED);
            List<OrderEvent> events = order.pullUncommittedEvents();
            assertThat(events).hasSize(2);
            assertThat(events.getFirst()).isInstanceOf(OrderInventoryReserved.class);
            assertThat(events.get(1)).isInstanceOf(OrderConfirmed.class);
        }

        @Test
        @DisplayName("é idempotente quando o estoque já foi reservado")
        void isIdempotent() {
            Order order = placedOrder();
            order.reserveInventory(UUID.randomUUID());
            order.pullUncommittedEvents();

            order.reserveInventory(UUID.randomUUID());

            assertThat(order.pullUncommittedEvents()).isEmpty();
            assertThat(order.status()).isEqualTo(OrderStatus.INVENTORY_RESERVED);
        }

        @Test
        @DisplayName("rejeita reservationId nulo")
        void rejectsNullReservationId() {
            Order order = placedOrder();
            assertThatNullPointerException().isThrownBy(() -> order.reserveInventory(null));
        }

        @Test
        @DisplayName("rejeita quando status não é PLACED nem PAID (e.g. CANCELLED)")
        void rejectsFromInvalidStatus() {
            Order order = placedOrder();
            order.cancel(OrderCancelled.CancellationReason.CUSTOMER_REQUESTED, null);

            assertThatThrownBy(() -> order.reserveInventory(UUID.randomUUID()))
                    .isInstanceOf(InvalidOrderStateTransitionException.class)
                    .hasMessageContaining("reserveInventory")
                    .hasMessageContaining("CANCELLED");
        }
    }

    @Nested
    @DisplayName("ship")
    class Ship {

        private Order confirmedOrder() {
            Order order = placedOrder();
            order.confirmPayment(UUID.randomUUID());
            order.reserveInventory(UUID.randomUUID());
            order.pullUncommittedEvents();
            return order;
        }

        @Test
        @DisplayName("a partir de CONFIRMED, transiciona para SHIPPED e emite OrderShipped")
        void fromConfirmedTransitionsToShipped() {
            Order order = confirmedOrder();
            var tracking = new TrackingNumber("BR123");

            order.ship(tracking, "Correios");

            assertThat(order.status()).isEqualTo(OrderStatus.SHIPPED);
            List<OrderEvent> events = order.pullUncommittedEvents();
            assertThat(events).hasSize(1);
            assertThat(events.getFirst()).isInstanceOfSatisfying(OrderShipped.class, e -> {
                assertThat(e.trackingNumber()).isEqualTo(tracking);
                assertThat(e.carrier()).isEqualTo("Correios");
            });
        }

        @Test
        @DisplayName("rejeita trackingNumber nulo")
        void rejectsNullTracking() {
            Order order = confirmedOrder();
            assertThatNullPointerException().isThrownBy(() -> order.ship(null, "Correios"));
        }

        @Test
        @DisplayName("rejeita carrier nulo ou em branco")
        void rejectsBlankCarrier() {
            Order order = confirmedOrder();
            var tracking = new TrackingNumber("BR123");

            assertThatThrownBy(() -> order.ship(tracking, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> order.ship(tracking, ""))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> order.ship(tracking, "   "))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejeita ship antes de CONFIRMED (PLACED)")
        void rejectedFromPlaced() {
            Order order = placedOrder();
            var tracking = new TrackingNumber("BR1");

            assertThatThrownBy(() -> order.ship(tracking, "Correios"))
                    .isInstanceOf(InvalidOrderStateTransitionException.class)
                    .hasMessageContaining("ship")
                    .hasMessageContaining("PLACED");
        }

        @Test
        @DisplayName("rejeita ship a partir de PAID")
        void rejectedFromPaid() {
            Order order = placedOrder();
            order.confirmPayment(UUID.randomUUID());
            var tracking = new TrackingNumber("BR1");

            assertThatThrownBy(() -> order.ship(tracking, "Correios"))
                    .isInstanceOf(InvalidOrderStateTransitionException.class);
        }

        @Test
        @DisplayName("rejeita ship a partir de INVENTORY_RESERVED (falta pagamento)")
        void rejectedFromInventoryReserved() {
            Order order = placedOrder();
            order.reserveInventory(UUID.randomUUID());
            var tracking = new TrackingNumber("BR1");

            assertThatThrownBy(() -> order.ship(tracking, "Correios"))
                    .isInstanceOf(InvalidOrderStateTransitionException.class)
                    .hasMessageContaining("ship")
                    .hasMessageContaining("INVENTORY_RESERVED");
        }
    }

    @Nested
    @DisplayName("deliver")
    class Deliver {

        private Order shippedOrder() {
            Order order = placedOrder();
            order.confirmPayment(UUID.randomUUID());
            order.reserveInventory(UUID.randomUUID());
            order.ship(new TrackingNumber("BR1"), "Correios");
            order.pullUncommittedEvents();
            return order;
        }

        @Test
        @DisplayName("a partir de SHIPPED, transiciona para DELIVERED e emite OrderDelivered")
        void transitionsToDelivered() {
            Order order = shippedOrder();

            order.deliver();

            assertThat(order.status()).isEqualTo(OrderStatus.DELIVERED);
            assertThat(order.status().isTerminal()).isTrue();
            List<OrderEvent> events = order.pullUncommittedEvents();
            assertThat(events).hasSize(1);
            assertThat(events.getFirst()).isInstanceOf(OrderDelivered.class);
        }

        @Test
        @DisplayName("rejeita deliver fora de SHIPPED")
        void rejectedOutsideShipped() {
            Order order = placedOrder();

            assertThatThrownBy(order::deliver)
                    .isInstanceOf(InvalidOrderStateTransitionException.class)
                    .hasMessageContaining("deliver");
        }

        @Test
        @DisplayName("rejeita deliver após DELIVERED")
        void rejectedAfterDelivered() {
            Order order = shippedOrder();
            order.deliver();

            assertThatThrownBy(order::deliver)
                    .isInstanceOf(InvalidOrderStateTransitionException.class);
        }
    }

    @Nested
    @DisplayName("cancel")
    class Cancel {

        @Test
        @DisplayName("rejeita reason nula")
        void rejectsNullReason() {
            Order order = placedOrder();
            assertThatNullPointerException().isThrownBy(() -> order.cancel(null, "details"));
        }

        @Test
        @DisplayName("a partir de PLACED, transiciona para CANCELLED")
        void cancelsFromPlaced() {
            Order order = placedOrder();
            order.pullUncommittedEvents();

            order.cancel(OrderCancelled.CancellationReason.CUSTOMER_REQUESTED, "Mudança de planos");

            assertThat(order.status()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(order.status().isTerminal()).isTrue();
            List<OrderEvent> events = order.pullUncommittedEvents();
            assertThat(events).hasSize(1);
            assertThat(events.getFirst()).isInstanceOfSatisfying(OrderCancelled.class, e -> {
                assertThat(e.reason()).isEqualTo(OrderCancelled.CancellationReason.CUSTOMER_REQUESTED);
                assertThat(e.details()).isEqualTo("Mudança de planos");
            });
        }

        @Test
        @DisplayName("permitido a partir de PAID, INVENTORY_RESERVED, CONFIRMED e SHIPPED")
        void cancelsFromAnyNonTerminalState() {
            // PAID
            Order paid = placedOrder();
            paid.confirmPayment(UUID.randomUUID());
            paid.cancel(OrderCancelled.CancellationReason.SYSTEM, null);
            assertThat(paid.status()).isEqualTo(OrderStatus.CANCELLED);

            // INVENTORY_RESERVED
            Order reserved = placedOrder();
            reserved.reserveInventory(UUID.randomUUID());
            reserved.cancel(OrderCancelled.CancellationReason.OUT_OF_STOCK, null);
            assertThat(reserved.status()).isEqualTo(OrderStatus.CANCELLED);

            // CONFIRMED
            Order confirmed = placedOrder();
            confirmed.confirmPayment(UUID.randomUUID());
            confirmed.reserveInventory(UUID.randomUUID());
            confirmed.cancel(OrderCancelled.CancellationReason.FRAUD_DETECTED, null);
            assertThat(confirmed.status()).isEqualTo(OrderStatus.CANCELLED);

            // SHIPPED
            Order shipped = placedOrder();
            shipped.confirmPayment(UUID.randomUUID());
            shipped.reserveInventory(UUID.randomUUID());
            shipped.ship(new TrackingNumber("BR1"), "Correios");
            shipped.cancel(OrderCancelled.CancellationReason.SYSTEM, null);
            assertThat(shipped.status()).isEqualTo(OrderStatus.CANCELLED);
        }

        @Test
        @DisplayName("rejeita cancelamento em estado terminal DELIVERED")
        void rejectedFromDelivered() {
            Order order = placedOrder();
            order.confirmPayment(UUID.randomUUID());
            order.reserveInventory(UUID.randomUUID());
            order.ship(new TrackingNumber("BR1"), "Correios");
            order.deliver();

            assertThatThrownBy(() -> order.cancel(OrderCancelled.CancellationReason.SYSTEM, null))
                    .isInstanceOf(InvalidOrderStateTransitionException.class)
                    .hasMessageContaining("cancel");
        }

        @Test
        @DisplayName("rejeita cancelamento em estado terminal CANCELLED")
        void rejectedFromCancelled() {
            Order order = placedOrder();
            order.cancel(OrderCancelled.CancellationReason.CUSTOMER_REQUESTED, null);

            assertThatThrownBy(() -> order.cancel(OrderCancelled.CancellationReason.SYSTEM, null))
                    .isInstanceOf(InvalidOrderStateTransitionException.class);
        }
    }

    @Nested
    @DisplayName("event-sourcing plumbing")
    class EventSourcing {

        @Test
        @DisplayName("pullUncommittedEvents retorna snapshot e limpa o buffer")
        void pullClearsBuffer() {
            Order order = placedOrder();
            assertThat(order.hasUncommittedEvents()).isTrue();

            List<OrderEvent> first = order.pullUncommittedEvents();
            assertThat(first).hasSize(1);
            assertThat(order.hasUncommittedEvents()).isFalse();
            assertThat(order.pullUncommittedEvents()).isEmpty();
        }

        @Test
        @DisplayName("pullUncommittedEvents retorna lista imutável")
        void pullReturnsImmutableList() {
            Order order = placedOrder();
            List<OrderEvent> events = order.pullUncommittedEvents();

            assertThatThrownBy(() -> events.add(null))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("version incrementa a cada evento aplicado")
        void versionIncrementsPerEvent() {
            Order order = placedOrder();
            assertThat(order.version()).isEqualTo(1L);

            order.confirmPayment(UUID.randomUUID());
            assertThat(order.version()).isEqualTo(2L);

            order.reserveInventory(UUID.randomUUID());
            // OrderInventoryReserved + OrderConfirmed
            assertThat(order.version()).isEqualTo(4L);
        }
    }

    @Nested
    @DisplayName("loadFromHistory")
    class LoadFromHistory {

        @Test
        @DisplayName("rejeita histórico nulo")
        void rejectsNullHistory() {
            assertThatNullPointerException()
                    .isThrownBy(() -> Order.loadFromHistory(null, CLOCK))
                    .withMessageContaining("history");
        }

        @Test
        @DisplayName("rejeita clock nulo")
        void rejectsNullClock() {
            assertThatNullPointerException()
                    .isThrownBy(() -> Order.loadFromHistory(List.of(), null))
                    .withMessageContaining("clock");
        }

        @Test
        @DisplayName("rejeita histórico vazio")
        void rejectsEmptyHistory() {
            assertThatThrownBy(() -> Order.loadFromHistory(List.of(), CLOCK))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("empty history");
        }

        @Test
        @DisplayName("reidrata o agregado a partir do stream completo")
        void rehydratesFromFullStream() {
            Order original = placedOrder();
            original.confirmPayment(UUID.randomUUID());
            original.reserveInventory(UUID.randomUUID());
            original.ship(new TrackingNumber("BR1"), "Correios");
            original.deliver();

            // capture all events emitted
            List<OrderEvent> history = List.copyOf(original.pullUncommittedEvents());

            Order rehydrated = Order.loadFromHistory(history, CLOCK);

            assertThat(rehydrated.id()).isEqualTo(original.id());
            assertThat(rehydrated.customerId()).isEqualTo(original.customerId());
            assertThat(rehydrated.items()).containsExactlyElementsOf(original.items());
            assertThat(rehydrated.shippingAddress()).isEqualTo(original.shippingAddress());
            assertThat(rehydrated.status()).isEqualTo(OrderStatus.DELIVERED);
            assertThat(rehydrated.isPaymentConfirmed()).isTrue();
            assertThat(rehydrated.isInventoryReserved()).isTrue();
            assertThat(rehydrated.totalAmount()).isEqualTo(original.totalAmount());
        }

        @Test
        @DisplayName("não deixa eventos pendentes ao reidratar")
        void rehydrateLeavesNoUncommittedEvents() {
            Order original = placedOrder();
            List<OrderEvent> history = original.pullUncommittedEvents();

            Order rehydrated = Order.loadFromHistory(history, CLOCK);

            assertThat(rehydrated.hasUncommittedEvents()).isFalse();
            assertThat(rehydrated.pullUncommittedEvents()).isEmpty();
        }

        @Test
        @DisplayName("version reflete a quantidade de eventos no histórico")
        void versionMatchesHistorySize() {
            Order original = placedOrder();
            original.confirmPayment(UUID.randomUUID()); // +1
            original.reserveInventory(UUID.randomUUID()); // +1 + OrderConfirmed (+1)
            List<OrderEvent> history = original.pullUncommittedEvents();
            assertThat(history).hasSize(4);

            Order rehydrated = Order.loadFromHistory(history, CLOCK);

            assertThat(rehydrated.version()).isEqualTo(4L);
        }
    }
}
