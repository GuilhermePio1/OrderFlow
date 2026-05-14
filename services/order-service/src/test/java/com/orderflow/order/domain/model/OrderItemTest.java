package com.orderflow.order.domain.model;

import com.orderflow.order.domain.model.valueobject.Money;
import com.orderflow.order.domain.model.valueobject.ProductId;
import com.orderflow.order.domain.model.valueobject.Quantity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Currency;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@DisplayName("OrderItem — entidade filha do agregado Order")
class OrderItemTest {

    private static final ProductId PRODUCT = ProductId.of(UUID.randomUUID());
    private static final Money PRICE = Money.of("9.90", "BRL");
    private static final Quantity QTY = Quantity.of(3);

    @Test
    @DisplayName("rejeita productId nulo")
    void rejectsNullProductId() {
        assertThatNullPointerException()
                .isThrownBy(() -> new OrderItem(null, QTY, PRICE))
                .withMessageContaining("productId");
    }

    @Test
    @DisplayName("rejeita quantity nula")
    void rejectsNullQuantity() {
        assertThatNullPointerException()
                .isThrownBy(() -> new OrderItem(PRODUCT, null, PRICE))
                .withMessageContaining("quantity");
    }

    @Test
    @DisplayName("rejeita unitPrice nulo")
    void rejectsNullUnitPrice() {
        assertThatNullPointerException()
                .isThrownBy(() -> new OrderItem(PRODUCT, QTY, null))
                .withMessageContaining("unitPrice");
    }

    @Test
    @DisplayName("rejeita unitPrice zero")
    void rejectsZeroUnitPrice() {
        assertThatThrownBy(() -> new OrderItem(PRODUCT, QTY, Money.zero(Currency.getInstance("BRL"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unitPrice must be positive");
    }

    @Test
    @DisplayName("rejeita unitPrice negativo")
    void rejectsNegativeUnitPrice() {
        assertThatThrownBy(() -> new OrderItem(PRODUCT, QTY, Money.of("-1.00", "BRL")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unitPrice must be positive");
    }

    @Test
    @DisplayName("subtotal = unitPrice * quantity, na mesma moeda")
    void subtotalMultipliesPriceByQuantity() {
        OrderItem item = new OrderItem(PRODUCT, Quantity.of(4), Money.of("2.50", "BRL"));

        assertThat(item.subtotal().amount()).isEqualByComparingTo("10.00");
        assertThat(item.subtotal().currency()).isEqualTo(PRICE.currency());
    }

    @Test
    @DisplayName("withQuantity gera nova instância preservando productId e unitPrice")
    void withQuantityProducesNewInstance() {
        OrderItem original = new OrderItem(PRODUCT, Quantity.of(1), PRICE);

        OrderItem copy = original.withQuantity(Quantity.of(5));

        assertThat(copy).isNotSameAs(original);
        assertThat(copy.productId()).isEqualTo(original.productId());
        assertThat(copy.unitPrice()).isEqualTo(original.unitPrice());
        assertThat(copy.quantity()).isEqualTo(Quantity.of(5));
        assertThat(original.quantity()).isEqualTo(Quantity.of(1));
    }

    @Test
    @DisplayName("igualdade segue todos os atributos (record)")
    void equalityByValue() {
        OrderItem a = new OrderItem(PRODUCT, QTY, PRICE);
        OrderItem b = new OrderItem(PRODUCT, QTY, PRICE);

        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
    }
}
