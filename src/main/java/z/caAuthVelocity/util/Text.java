package z.caAuthVelocity.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public final class Text {

    private static final LegacyComponentSerializer SERIALIZER = LegacyComponentSerializer.builder()
            .character('&')
            .hexCharacter('#')
            .hexColors()
            .build();

    private static final LegacyComponentSerializer SECTION_SERIALIZER = LegacyComponentSerializer.builder()
            .character('§')
            .hexColors()
            .build();

    private Text() {
    }

    public static Component color(String text) {
        return SERIALIZER.deserialize(text);
    }

    public static Component section(String text) {
        return SECTION_SERIALIZER.deserialize(text);
    }
}