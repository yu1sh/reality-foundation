package io.github.yu1sh.reality.foundation.forge;

import io.github.yu1sh.reality.gui.LocaleTag;
import net.minecraft.server.level.ServerPlayer;

/**
 * Server-owned preference boundary for actor projection. A later preference
 * or configuration Mod can implement this interface without allowing a
 * client packet to choose locale or streamer mode. The implementation must
 * return only bounded, non-sensitive values.
 */
public interface FoundationServerPreferencePort {
    LocaleTag localeFor(ServerPlayer player);

    boolean streamerModeFor(ServerPlayer player);

    /** Server-owned fallback for a console command with no player actor. */
    default LocaleTag consoleLocale() {
        return LocaleTag.of("en-US");
    }

    /** Server-owned redaction policy for console command output. */
    default boolean streamerModeForConsole() {
        return false;
    }
}
