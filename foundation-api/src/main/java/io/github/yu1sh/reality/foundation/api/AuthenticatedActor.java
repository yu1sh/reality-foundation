package io.github.yu1sh.reality.foundation.api;

import io.github.yu1sh.reality.gui.LocaleTag;
import io.github.yu1sh.reality.identity.ActorId;

import java.util.Objects;

/**
 * Server-authenticated actor context. Its values are created by the adapter;
 * no packet is allowed to construct or replace this object.
 */
public final class AuthenticatedActor {
    private final ActorId actorId;
    private final int permissionLevel;
    private final LocaleTag locale;
    private final boolean streamerMode;

    private AuthenticatedActor(
            ActorId actorId, int permissionLevel, LocaleTag locale, boolean streamerMode) {
        this.actorId = Objects.requireNonNull(actorId, "actorId");
        if (permissionLevel < 0 || permissionLevel > 4) {
            throw new IllegalArgumentException("Permission level must be between zero and four");
        }
        this.permissionLevel = permissionLevel;
        this.locale = Objects.requireNonNull(locale, "locale");
        this.streamerMode = streamerMode;
    }

    public static AuthenticatedActor of(
            ActorId actorId, int permissionLevel, LocaleTag locale, boolean streamerMode) {
        return new AuthenticatedActor(actorId, permissionLevel, locale, streamerMode);
    }

    public ActorId actorId() {
        return actorId;
    }

    public int permissionLevel() {
        return permissionLevel;
    }

    public LocaleTag locale() {
        return locale;
    }

    public boolean streamerMode() {
        return streamerMode;
    }

    public boolean mayViewAdminDiagnostics() {
        return permissionLevel >= 2 && !streamerMode;
    }

    @Override
    public String toString() {
        return "AuthenticatedActor[permissionLevel=" + permissionLevel
                + ", locale=" + locale + ", streamerMode=" + streamerMode + "]";
    }
}
