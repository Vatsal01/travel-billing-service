package com.example.billing.common;

import java.util.Locale;

public final class WireNames {

    private WireNames() {
    }

    public static String snakeCase(String propertyPath) {
        return propertyPath
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .toLowerCase(Locale.ROOT);
    }
}
