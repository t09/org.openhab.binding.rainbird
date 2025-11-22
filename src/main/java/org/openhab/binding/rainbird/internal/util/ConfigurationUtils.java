package org.openhab.binding.rainbird.internal.util;

import org.eclipse.jdt.annotation.Nullable;

/**
 * Helper for reading strongly-typed values from openHAB {@code Configuration} maps.
 */
public final class ConfigurationUtils {

    private ConfigurationUtils() {
    }

    public static int asInt(@Nullable Object value, int defaultValue) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            String trimmed = ((String) value).trim();
            if (trimmed.isEmpty()) {
                return defaultValue;
            }
            try {
                return Integer.parseInt(trimmed);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    public static boolean asBoolean(@Nullable Object value, boolean defaultValue) {
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        if (value instanceof String) {
            String trimmed = ((String) value).trim();
            if (trimmed.isEmpty()) {
                return defaultValue;
            }
            if ("true".equalsIgnoreCase(trimmed) || "yes".equalsIgnoreCase(trimmed)) {
                return true;
            }
            if ("false".equalsIgnoreCase(trimmed) || "no".equalsIgnoreCase(trimmed)) {
                return false;
            }
        }
        return defaultValue;
    }

    public static @Nullable String asString(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String) {
            String trimmed = ((String) value).trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        return value.toString();
    }
}
