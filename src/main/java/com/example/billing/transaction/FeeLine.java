package com.example.billing.transaction;

import java.math.BigDecimal;

public record FeeLine(String name, BigDecimal amount) {
}
