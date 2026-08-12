package io.github.yu1sh.reality.foundation.forge;

import io.github.yu1sh.reality.foundation.api.AuthenticatedActor;
import io.github.yu1sh.reality.gui.LocaleTag;
import io.github.yu1sh.reality.identity.ActorId;
import net.minecraft.server.level.ServerPlayer;

/** Converts only server-observed player permissions into an API actor. */
final class ForgeActors {
    private ForgeActors() {
    }

    static AuthenticatedActor from(
            ServerPlayer player, FoundationServerPreferencePort preferencePort) {
        int permission = player.hasPermissions(4) ? 4 : player.hasPermissions(2) ? 2 : 0;
        LocaleTag locale = LocaleTag.of("en-US");
        boolean streamerMode = false;
        try {
            LocaleTag configured = preferencePort.localeFor(player);
            if (configured != null && (configured.equals(LocaleTag.of("en-US"))
                    || configured.equals(LocaleTag.of("ja-JP")))) {
                locale = configured;
            }
            streamerMode = preferencePort.streamerModeFor(player);
        } catch (RuntimeException ignored) {
            // A broken optional preference owner cannot grant admin visibility.
        }
        return AuthenticatedActor.of(
                ActorId.of(player.getUUID().toString()), permission,
                locale, streamerMode);
    }
}
