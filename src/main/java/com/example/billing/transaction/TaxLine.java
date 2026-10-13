package com.example.billing.transaction;

import java.math.BigDecimal;

public record TaxLine(String name, BigDecimal rate, BigDecimal amount) {
}
