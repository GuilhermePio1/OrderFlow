package com.orderflow.payment.domain.model.valueobject;

import com.orderflow.payment.domain.exception.CurrencyMismatchException;
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
        void normalizesScale() {
            Money money = Money.of(new BigDecimal("10.5"), BRL);

            assertThat(money.amount().scale()).isEqualTo(BRL.getDefaultFractionDigits());
            assertThat(money.amount()).isEqualByComparingTo("10.50");
        }

        @Test
        @DisplayName("usa HALF_EVEN como modo de arredondamento")
        void roundsHalfEven() {
            assertThat(Money.of(new BigDecimal("1.225"), BRL).amount())
                    .isEqualByComparingTo("1.22");
            assertThat(Money.of(new BigDecimal("1.235"), BRL).amount())
                    .isEqualByComparingTo("1.24");
        }

        @Test
        @DisplayName("Money.zero produz amount zero")
        void zeroFactory() {
            Money zero = Money.zero(BRL);
            assertThat(zero.isZero()).isTrue();
            assertThat(zero.currency()).isEqualTo(BRL);
        }

        @Test
        @DisplayName("imutável: plus não modifica original")
        void immutable() {
            Money original = Money.of("10.00", "BRL");
            original.plus(Money.of("5.00", "BRL"));
            assertThat(original.amount()).isEqualByComparingTo("10.00");
        }
    }

    @Nested
    @DisplayName("aritmética")
    class Arithmetic {

        @Test
        void plus() {
            assertThat(Money.of("10.00", "BRL").plus(Money.of("2.50", "BRL")).amount())
                    .isEqualByComparingTo("12.50");
        }

        @Test
        void minus() {
            assertThat(Money.of("10.00", "BRL").minus(Money.of("2.50", "BRL")).amount())
                    .isEqualByComparingTo("7.50");
        }

        @Test
        void multiply() {
            assertThat(Money.of("3.50", "BRL").multiply(4).amount())
                    .isEqualByComparingTo("14.00");
        }

        @Test
        @DisplayName("plus rejeita moeda diferente")
        void plusRejectsDifferentCurrency() {
            assertThatThrownBy(() -> Money.of("1.00", "BRL").plus(Money.of("1.00", "USD")))
                    .isInstanceOf(CurrencyMismatchException.class)
                    .hasMessageContaining("BRL")
                    .hasMessageContaining("USD");
        }

        @Test
        @DisplayName("minus rejeita moeda diferente")
        void minusRejectsDifferentCurrency() {
            assertThatThrownBy(() -> Money.of("1.00", "BRL").minus(Money.of("1.00", "USD")))
                    .isInstanceOf(CurrencyMismatchException.class);
        }
    }

    @Nested
    @DisplayName("comparações")
    class Comparisons {

        @Test
        void greaterAndLess() {
            Money a = Money.of("10.00", "BRL");
            Money b = Money.of("5.00", "BRL");

            assertThat(a.isGreaterThan(b)).isTrue();
            assertThat(b.isLessThan(a)).isTrue();
            assertThat(a.isGreaterThan(a)).isFalse();
            assertThat(a.isLessThan(a)).isFalse();
        }

        @Test
        void isEqualValueIgnoresScale() {
            Money a = Money.of(new BigDecimal("1.5"), BRL);
            Money b = Money.of(new BigDecimal("1.50"), BRL);
            assertThat(a.isEqualValue(b)).isTrue();
        }

        @Test
        @DisplayName("comparações rejeitam moeda diferente")
        void comparisonsRejectDifferentCurrency() {
            Money brl = Money.of("1.00", "BRL");
            Money usd = Money.of("1.00", "USD");

            assertThatThrownBy(() -> brl.isGreaterThan(usd))
                    .isInstanceOf(CurrencyMismatchException.class);
            assertThatThrownBy(() -> brl.isLessThan(usd))
                    .isInstanceOf(CurrencyMismatchException.class);
            assertThatThrownBy(() -> brl.isEqualValue(usd))
                    .isInstanceOf(CurrencyMismatchException.class);
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
        }

        @Test
        void negative() {
            Money m = Money.of("-1.00", "BRL");
            assertThat(m.isNegative()).isTrue();
        }
    }

    @Nested
    @DisplayName("igualdade")
    class Equality {

        @Test
        void equalAfterNormalization() {
            Money a = Money.of(new BigDecimal("1.5"), BRL);
            Money b = Money.of(new BigDecimal("1.50"), BRL);
            assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        }

        @Test
        void differentByCurrency() {
            assertThat(Money.of("1.00", "BRL")).isNotEqualTo(Money.of("1.00", "USD"));
        }
    }
}
