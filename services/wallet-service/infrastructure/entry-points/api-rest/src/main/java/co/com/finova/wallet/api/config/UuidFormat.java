package co.com.finova.wallet.api.config;

import java.util.regex.Pattern;

/** Canonical UUID text (8-4-4-4-12 hex), checked before a header value is trusted. */
public final class UuidFormat {

    private static final Pattern UUID =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private UuidFormat() {
    }

    public static boolean isUuid(String value) {
        return value != null && UUID.matcher(value).matches();
    }
}
