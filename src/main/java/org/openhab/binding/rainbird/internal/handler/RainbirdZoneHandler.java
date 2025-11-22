package org.openhab.binding.rainbird.internal.handler;

import static org.openhab.binding.rainbird.internal.RainbirdBindingConstants.CHANNEL_ZONE_FLOW;
import static org.openhab.binding.rainbird.internal.RainbirdBindingConstants.CHANNEL_ZONE_REMAINING_TIME;
import static org.openhab.binding.rainbird.internal.RainbirdBindingConstants.CHANNEL_ZONE_SWITCH;
import static org.openhab.binding.rainbird.internal.RainbirdBindingConstants.CONFIG_DEFAULT_DURATION;
import static org.openhab.binding.rainbird.internal.RainbirdBindingConstants.CONFIG_ZONE_NUMBER;

import java.io.IOException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.rainbird.internal.handler.RainbirdBridgeHandler.Client;
import org.openhab.binding.rainbird.internal.net.RainbirdClient.PollingResult;
import org.openhab.binding.rainbird.internal.net.RainbirdClient.ZoneStatus;
import org.openhab.binding.rainbird.internal.net.RainbirdCommandResult;
import org.openhab.binding.rainbird.internal.util.ConfigurationUtils;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Zonen-Thing (Child). Steuert eine einzelne Bewässerungszone.
 */
public class RainbirdZoneHandler extends BaseThingHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(RainbirdZoneHandler.class);

    private int zoneNumber = 1;
    private int defaultDurationSec = 300;
    private @Nullable ScheduledFuture<?> pollTask;

    public RainbirdZoneHandler(Thing thing) {
        super(thing);
    }

    @Override
    public void initialize() {
        Bridge bridge = getBridge();
        if (bridge == null || !(bridge.getHandler() instanceof RainbirdBridgeHandler)) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE, "Bridge fehlt/ist offline");
            return;
        }

        Configuration cfg = getThing().getConfiguration();
        zoneNumber = Math.max(1, ConfigurationUtils.asInt(cfg.get(CONFIG_ZONE_NUMBER), 1));
        defaultDurationSec = Math.max(0, ConfigurationUtils.asInt(cfg.get(CONFIG_DEFAULT_DURATION), 300));

        updateStatus(ThingStatus.UNKNOWN);
        RainbirdBridgeHandler bridgeHandler = (RainbirdBridgeHandler) bridge.getHandler();
        int interval = bridgeHandlerPollingInterval(bridgeHandler);
        pollTask = scheduler.scheduleWithFixedDelay(this::poll, 2, interval, TimeUnit.SECONDS);
    }

    private void poll() {
        Bridge bridge = getBridge();
        if (bridge == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE, "Keine Bridge");
            return;
        }

        RainbirdBridgeHandler bridgeHandler = (RainbirdBridgeHandler) bridge.getHandler();
        if (bridgeHandler == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE, "Bridge-Handler fehlt");
            return;
        }

        Client client = bridgeHandler.getClient();
        if (client == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE, "Client nicht verfügbar");
            return;
        }

        PollingResult result = client.getLastResult();
        if (result == null) {
            try {
                result = client.poll();
            } catch (IOException e) {
                LOGGER.debug("Fehler beim Polling des Rain Bird Controllers", e);
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        applyZoneState(result.getZoneStatus());
    }

    @Override
    public void dispose() {
        ScheduledFuture<?> t = pollTask;
        if (t != null) {
            t.cancel(true);
            pollTask = null;
        }
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        String id = channelUID.getId();

        if (CHANNEL_ZONE_SWITCH.equals(id)) {
            handleSwitchCommand(command);
        }
    }

    private void applyZoneState(ZoneStatus status) {
        boolean active = status.getActiveZone() == zoneNumber;
        updateState(CHANNEL_ZONE_SWITCH, active ? OnOffType.ON : OnOffType.OFF);
        int remaining = active ? status.getRemainingRuntime() : 0;
        updateState(CHANNEL_ZONE_REMAINING_TIME, new DecimalType(remaining));
        updateState(CHANNEL_ZONE_FLOW, new DecimalType(0));
        updateStatus(ThingStatus.ONLINE);
    }

    private void handleSwitchCommand(Command command) {
        Bridge bridge = getBridge();
        if (bridge == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE, "Keine Bridge");
            return;
        }

        RainbirdBridgeHandler bridgeHandler = (RainbirdBridgeHandler) bridge.getHandler();
        if (bridgeHandler == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE, "Bridge-Handler fehlt");
            return;
        }

        Client client = bridgeHandler.getClient();
        if (client == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.BRIDGE_OFFLINE, "Client nicht verfügbar");
            return;
        }

        if (command == OnOffType.ON) {
            int minutes = Math.max(1, (int) Math.round(defaultDurationSec / 60.0));
            RainbirdCommandResult result = client.runStation(zoneNumber, minutes);
            if (!result.isSuccess()) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Zonenstart fehlgeschlagen");
            }
        } else if (command == OnOffType.OFF) {
            RainbirdCommandResult result = client.stopAllZones();
            if (!result.isSuccess()) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Zonenstopp fehlgeschlagen");
            }
        }
    }

    private int bridgeHandlerPollingInterval(@Nullable RainbirdBridgeHandler handler) {
        if (handler == null) {
            return 30;
        }
        return Math.max(5, handler.getPollingIntervalSeconds());
    }
}
