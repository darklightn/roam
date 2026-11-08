package com.roam.domain;

import java.math.BigDecimal;
import java.util.Objects;

public record Money(BigDecimal amount, String currency) {

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must not be negative");
        }
    }

    public static Money cny(String amount) {
        return new Money(new BigDecimal(amount), "CNY");
    }

    public static Money cny(BigDecimal amount) {
        return new Money(amount, "CNY");
    }

    public Money plus(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("currency mismatch");
        }
        return new Money(amount.add(other.amount), currency);
    }

    public boolean isGreaterThan(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException("currency mismatch");
        }
        return amount.compareTo(other.amount) > 0;
    }

    public boolean isAtMost(Money other) {
        return amount.compareTo(other.amount) <= 0;
    }

    @Override
    public String toString() {
        return amount.stripTrailingZeros().toPlainString() + " " + currency;
    }
}
