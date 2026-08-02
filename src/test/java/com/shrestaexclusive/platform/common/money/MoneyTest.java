package com.shrestaexclusive.platform.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void storesPaiseExactly() {
        Money price = Money.ofPaise(69_950);

        assertThat(price.paise()).isEqualTo(69_950);
    }

    @Test
    void rejectsNegativePaise() {
        assertThatThrownBy(() -> Money.ofPaise(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Money cannot be negative");
    }

    @Test
    void usesExactArithmetic() {
        Money total = Money.ofPaise(10_000)
                .plus(Money.ofPaise(2_500))
                .multipliedBy(2);

        assertThat(total).isEqualTo(Money.ofPaise(25_000));
    }

    @Test
    void preventsNegativeResult() {
        assertThatThrownBy(() -> Money.ofPaise(100).minus(Money.ofPaise(101)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Money cannot be negative");
    }
}
