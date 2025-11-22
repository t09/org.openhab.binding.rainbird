package org.openhab.binding.rainbird.internal.net;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;

/**
 * Result of a manual command issued to the controller via the SIP tunnel.
 */
@NonNullByDefault
public final class RainbirdCommandResult {

    private final boolean success;
    private final int commandEcho;
    private final @Nullable Integer errorCode;

    public RainbirdCommandResult(boolean success, int commandEcho, @Nullable Integer errorCode) {
        this.success = success;
        this.commandEcho = commandEcho;
        this.errorCode = errorCode;
    }

    public boolean isSuccess() {
        return success;
    }

    public int getCommandEcho() {
        return commandEcho;
    }

    public @Nullable Integer getErrorCode() {
        return errorCode;
    }
}
