package io.github.yu1sh.reality.foundation.forge;

import io.github.yu1sh.reality.gui.LocaleTag;
import net.minecraft.server.level.ServerPlayer;

/**
 * Safe server-owned fallback configuration. It is intentionally in-memory;
 * persistence belongs to a later preference owner that implements the port.
 * The package-visible setters are used by Forge GameTest to exercise the
 * server-controlled projection without introducing a client preference
 * packet or a foundation-owned database/config schema.
 */
public final class DefaultFoundationServerPreferencePort implements FoundationServerPreferencePort {
    private volatile LocaleTag defaultLocale = LocaleTag.of("en-US");
    private volatile boolean streamerMode;

    @Override
    public LocaleTag localeFor(ServerPlayer player) {
        return defaultLocale;
    }

    @Override
    public boolean streamerModeFor(ServerPlayer player) {
        return streamerMode;
    }

    @Override
    public LocaleTag consoleLocale() {
        return defaultLocale;
    }

    @Override
    public boolean streamerModeForConsole() {
        return streamerMode;
    }

    public void setDefaultLocale(LocaleTag locale) {
        if (locale == null || !(locale.equals(LocaleTag.of("en-US"))
                || locale.equals(LocaleTag.of("ja-JP")))) {
            throw new IllegalArgumentException("Only en-US and ja-JP are supported");
        }
        defaultLocale = locale;
    }

    public void setStreamerMode(boolean enabled) {
        streamerMode = enabled;
    }
}
