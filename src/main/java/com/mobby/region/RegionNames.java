package com.mobby.region;

import java.util.Locale;
import java.util.regex.Pattern;

/** Validation and normalisation of region names. */
public final class RegionNames {

    private static final Pattern ALLOWED = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private RegionNames() {
    }

    public static boolean isValid(String name) {
        return name != null && ALLOWED.matcher(name).matches();
    }

    /** Case insensitive lookup key. */
    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
