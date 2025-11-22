package org.openhab.binding.rainbird.internal.config;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Configuration object for the Rain Bird bridge.
 */
@NonNullByDefault
public class RainbirdConfiguration {

    /**
     * Host name or IP address of the Rain Bird controller.
     */
    public String host = "";

    /**
     * TCP port used to connect to the controller.
     */
    public int port = 80;

    /**
     * Base path of the controller endpoint. Defaults to {@code /stick}.
     */
    public String basePath = "/stick";

    /**
     * Request timeout in milliseconds.
     */
    public int timeoutMillis = 5000;

    /**
     * Selected controller model.
     */
    public String controllerModel = "RC2";

    /**
     * Password that protects the Rain Bird controller.
     */
    public String password = "";

    /**
     * Polling interval in seconds.
     */
    public int pollingInterval = 30;

    /**
     * Cloud support is intentionally disabled for the first iteration. The UI shows the
     * flag but keeps it immutable.
     */
    public boolean cloudMode = false;
}
