package com.orderflow.order.domain.model.valueobject;

import com.orderflow.order.domain.exception.CurrencyMismatchException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Money — value object")
class MoneyTest {

    private static final Currency BRL = Currency.getInstance("BRL");
    private static final Currency USD = Currency.getInstance("USD");

    @Nested
    @DisplayName("construção")
    class Construction {

        @Test
        @DisplayName("rejeita amount nulo")
        void rejectsNullAmount() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new Money(null, BRL))
                    .withMessageContaining("amount");
        }

        @Test
        @DisplayName("rejeita currency nula")
        void rejectsNullCurrency() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new Money(BigDecimal.ONE, null))
                    .withMessageContaining("currency");
        }

        @Test
        @DisplayName("normaliza scale para os fraction digits da moeda")
        void normalizesScaleToCurrencyFractionDigits() {
            Money money = Money.of(new BigDecimal("10.5"), BRL);

            assertThat(money.amount().scale()).isEqualTo(BRL.getDefaultFractionDigits());
            assertThat(money.amount()).isEqualByComparingTo("10.50");
        }

        @Test
        @DisplayName("usa HALF_EVEN como modo de arredondamento")
        void roundsUsingHalfEven() {
            assertThat(Money.of(new BigDecimal("1.225"), BRL).amount())
                    .isEqualByComparingTo("1.22");
            assertThat(Money.of(new BigDecimal("1.235"), BRL).amount())
                    .isEqualByComparingTo("1.24");
        }

        @Test
        @DisplayName("Money.of(String, String) interpreta ambos os parâmetros")
        void factoryFromStrings() {
            Money money = Money.of("99.99", "USD");

            assertThat(money.currency()).isEqualTo(USD);
            assertThat(money.amount()).isEqualByComparingTo("99.99");
        }

        @Test
        @DisplayName("Money.zero produz amount zero na moeda informada")
        void zeroFactory() {
            Money zero = Money.zero(BRL);

            assertThat(zero.isZero()).isTrue();
            assertThat(zero.currency()).isEqualTo(BRL);
        }

        @Test
        @DisplayName("operações matemáticas não modificam a instância original (imutabilidade)")
        void immutability() {
            Money original = Money.of("10.00", "BRL");
            Money toAdd = Money.of("5.00", "BRL");

            original.plus(toAdd);

            assertThat(original.amount()).isEqualByComparingTo("10.00");
        }
    }

    @Nested
    @DisplayName("aritmética")
    class Arithmetic {

        @Test
        @DisplayName("plus soma valores na mesma moeda")
        void plusSums() {
            Money a = Money.of("10.00", "BRL");
            Money b = Money.of("2.50", "BRL");

            assertThat(a.plus(b).amount()).isEqualByComparingTo("12.50");
        }

        @Test
        @DisplayName("minus subtrai valores na mesma moeda")
        void minusSubtracts() {
            Money a = Money.of("10.00", "BRL");
            Money b = Money.of("2.50", "BRL");

            assertThat(a.minus(b).amount()).isEqualByComparingTo("7.50");
        }

        @Test
        @DisplayName("multiply escala por inteiro")
        void multiplyScales() {
            Money price = Money.of("3.50", "BRL");

            assertThat(price.multiply(4).amount()).isEqualByComparingTo("14.00");
        }

        @Test
        @DisplayName("plus rejeita Money de moeda diferente")
        void plusRejectsCurrencyMismatch() {
            Money brl = Money.of("10.00", "BRL");
            Money usd = Money.of("10.00", "USD");

            assertThatThrownBy(() -> brl.plus(usd))
                    .isInstanceOf(CurrencyMismatchException.class)
                    .hasMessageContaining("BRL")
                    .hasMessageContaining("USD");
        }

        @Test
        @DisplayName("minus rejeita Money de moeda diferente")
        void minusRejectsCurrencyMismatch() {
            Money brl = Money.of("10.00", "BRL");
            Money usd = Money.of("10.00", "USD");

            assertThatThrownBy(() -> brl.minus(usd))
                    .isInstanceOf(CurrencyMismatchException.class);
        }

        @Test
        @DisplayName("operações preservam a moeda")
        void operationsPreserveCurrency() {
            Money a = Money.of("10.00", "BRL");
            Money b = Money.of("2.00", "BRL");

            assertThat(a.plus(b).currency()).isEqualTo(BRL);
            assertThat(a.minus(b).currency()).isEqualTo(BRL);
            assertThat(a.multiply(3).currency()).isEqualTo(BRL);
        }
    }

    @Nested
    @DisplayName("predicados de sinal")
    class SignPredicates {

        @Test
        void positive() {
            Money m = Money.of("0.01", "BRL");
            assertThat(m.isPositive()).isTrue();
            assertThat(m.isNegative()).isFalse();
            assertThat(m.isZero()).isFalse();
        }

        @Test
        void zero() {
            Money m = Money.zero(BRL);
            assertThat(m.isZero()).isTrue();
            assertThat(m.isPositive()).isFalse();
            assertThat(m.isNegative()).isFalse();
        }

        @Test
        void negative() {
            Money m = Money.of("-1.00", "BRL");
            assertThat(m.isNegative()).isTrue();
            assertThat(m.isPositive()).isFalse();
            assertThat(m.isZero()).isFalse();
        }
    }

    @Nested
    @DisplayName("igualdade")
    class Equality {

        @Test
        @DisplayName("Money é igual quando amount e currency coincidem após normalização")
        void equalAfterNormalization() {
            Money a = Money.of(new BigDecimal("1.5"), BRL);
            Money b = Money.of(new BigDecimal("1.50"), BRL);

            assertThat(a).isEqualTo(b);
            assertThat(a).hasSameHashCodeAs(b);
        }

        @Test
        @DisplayName("Money é diferente quando moedas divergem")
        void differentByCurrency() {
            Money brl = Money.of("1.00", "BRL");
            Money usd = Money.of("1.00", "USD");

            assertThat(brl).isNotEqualTo(usd);
        }
    }

    @Nested
    @DisplayName("formatações inválidas")
    class InvalidFormats {

        @Test
        @DisplayName("rejeita formatação numérica inválida")
        void rejectsInvalidAmountFormat() {
            assertThatThrownBy(() -> Money.of("abc", "BRL"))
                    .isInstanceOf(NumberFormatException.class);
        }

        @Test
        @DisplayName("rejeita código de moeda inexistente")
        void rejectsInvalidCurrencyCode() {
            assertThatThrownBy(() -> Money.of("10.00", "ZZZ"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
