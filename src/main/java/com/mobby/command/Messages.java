package com.mobby.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/** Small helper for consistent, colourised command output. */
public final class Messages {

    private static final String PREFIX = "[MobBy] ";

    private Messages() {
    }

    public static Component info(String text) {
        return Component.text(PREFIX + text, NamedTextColor.GRAY);
    }

    public static Component ok(String text) {
        return Component.text(PREFIX + text, NamedTextColor.GREEN);
    }

    public static Component warn(String text) {
        return Component.text(PREFIX + text, NamedTextColor.YELLOW);
    }

    public static Component error(String text) {
        return Component.text(PREFIX + text, NamedTextColor.RED);
    }

    public static Component header(String text) {
        return Component.text(text, NamedTextColor.GOLD);
    }

    public static Component line(String label, String value) {
        return Component.text(label + ": ", NamedTextColor.GRAY)
                .append(Component.text(value, NamedTextColor.WHITE));
    }

    public static Component usage(String usage, String description, String permission) {
        return Component.text("  " + usage, NamedTextColor.AQUA)
                .append(Component.text(" - " + description, NamedTextColor.GRAY))
                .append(Component.text(" [" + permission + "]", NamedTextColor.DARK_GRAY));
    }
}
