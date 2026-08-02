package com.shrestaexclusive.platform.common.money;

import java.util.Objects;

public record Money(long paise) implements Comparable<Money> {

    public static final Money ZERO = new Money(0);

    public Money {
        if (paise < 0) {
            throw new IllegalArgumentException("Money cannot be negative");
        }
    }

    public static Money ofPaise(long paise) {
        return new Money(paise);
    }

    public Money plus(Money other) {
        Objects.requireNonNull(other, "other");
        return new Money(Math.addExact(this.paise, other.paise));
    }

    public Money minus(Money other) {
        Objects.requireNonNull(other, "other");
        return new Money(Math.subtractExact(this.paise, other.paise));
    }

    public Money multipliedBy(int quantity) {
        if (quantity < 0) {
            throw new IllegalArgumentException("Quantity cannot be negative");
        }
        return new Money(Math.multiplyExact(this.paise, quantity));
    }

    public boolean isZero() {
        return paise == 0;
    }

    @Override
    public int compareTo(Money other) {
        Objects.requireNonNull(other, "other");
        return Long.compare(this.paise, other.paise);
    }
}
