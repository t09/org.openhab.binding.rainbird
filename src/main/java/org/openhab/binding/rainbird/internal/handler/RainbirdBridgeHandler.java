package org.openhab.binding.rainbird.internal.handler;

import static org.openhab.binding.rainbird.internal.RainbirdBindingConstants.*;

import java.io.IOException;
import java.net.URI;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.rainbird.internal.config.RainbirdConfiguration;
import org.openhab.binding.rainbird.internal.net.RainbirdClient;
import org.openhab.binding.rainbird.internal.net.RainbirdClient.CombinedState;
import org.openhab.binding.rainbird.internal.net.RainbirdClient.ControllerStatus;
import org.openhab.binding.rainbird.internal.net.RainbirdClient.ControllerFirmwareVersion;
import org.openhab.binding.rainbird.internal.net.RainbirdClient.ModelAndVersion;
import org.openhab.binding.rainbird.internal.net.RainbirdClient.NetworkStatus;
import org.openhab.binding.rainbird.internal.net.RainbirdClient.PollingResult;
import org.openhab.binding.rainbird.internal.net.RainbirdClient.ProgramStatus;
import org.openhab.binding.rainbird.internal.net.RainbirdClient.WifiStatus;
import org.openhab.binding.rainbird.internal.net.RainbirdClient.ZoneStatus;
import org.openhab.binding.rainbird.internal.net.RainbirdClient.WeatherStatus;
import org.openhab.binding.rainbird.internal.net.RainbirdClient.ZipCodeInfo;
import org.openhab.binding.rainbird.internal.net.RainbirdCommandResult;
import org.openhab.binding.rainbird.internal.util.ConfigurationUtils;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.library.unit.Units;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Channel;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.type.ChannelTypeUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.BaseBridgeHandler;
import org.openhab.core.thing.binding.builder.ChannelBuilder;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.types.Command;
import org.openhab.core.types.UnDefType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verantwortlich für Verbindungsmanagement und die Controller-Kanäle.
 */
public class RainbirdBridgeHandler extends BaseBridgeHandler {

    private static final int DEFAULT_REFRESH_SECONDS = 30;
    private static final int DEFAULT_MANUAL_DURATION_MINUTES = 5;
    private static final int MAX_MANUAL_DURATION_MINUTES = 100;

    private final Logger logger = LoggerFactory.getLogger(RainbirdBridgeHandler.class);

    private @Nullable Client client;
    private @Nullable ScheduledFuture<?> pollTask;
    private int pollingIntervalSeconds = DEFAULT_REFRESH_SECONDS;
    private String deviceId = "controller";
    private @Nullable ModelAndVersion cachedModel;
    private @Nullable ControllerFirmwareVersion cachedControllerFirmware;
    private @Nullable ZipCodeInfo cachedZipCode;
    private @Nullable String cachedCustomStationName;
    private final Map<Integer, Integer> zoneDurationsMinutes = new ConcurrentHashMap<>();
    private volatile int lastDynamicZoneCount = 0;
    private volatile @Nullable ZoneStatus lastZoneStatus;

    public RainbirdBridgeHandler(Bridge bridge) {
        super(bridge);
    }

    @Override
    public void initialize() {
        Configuration cfg = getThing().getConfiguration();
        String host = ConfigurationUtils.asString(cfg.get(CONFIG_HOST));
        int port = ConfigurationUtils.asInt(cfg.get(CONFIG_PORT), 80);
        String password = ConfigurationUtils.asString(cfg.get(CONFIG_PASSWORD));
        pollingIntervalSeconds = Math.max(5, ConfigurationUtils.asInt(cfg.get(CONFIG_REFRESH), DEFAULT_REFRESH_SECONDS));
        String configuredDeviceId = ConfigurationUtils.asString(cfg.get(CONFIG_DEVICE_ID));
        deviceId = configuredDeviceId != null ? configuredDeviceId : "controller";
        cachedModel = null;
        cachedControllerFirmware = null;
        cachedZipCode = null;
        cachedCustomStationName = null;
        zoneDurationsMinutes.clear();
        lastDynamicZoneCount = 0;
        lastZoneStatus = null;

        RainbirdConfiguration configuration;
        try {
            configuration = createConfiguration(host, port, password, pollingIntervalSeconds,
                    ConfigurationUtils.asInt(cfg.get(CONFIG_TIMEOUT), 5000));
        } catch (IllegalArgumentException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, e.getMessage());
            return;
        }

        try {
            RainbirdClient localClient = new RainbirdClient(configuration);
            RainbirdClient cloudClient = new RainbirdClient(createCloudConfiguration(configuration.timeoutMillis));
            client = new Client(localClient, cloudClient);
            updateStatus(ThingStatus.UNKNOWN);
        } catch (Exception e) {
            logger.warn("Fehler beim Initialisieren des Rain-Bird-Clients", e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, e.getMessage());
            return;
        }

        pollTask = scheduler.scheduleWithFixedDelay(this::pollController, 0, pollingIntervalSeconds, TimeUnit.SECONDS);
    }

    @Override
    public void dispose() {
        ScheduledFuture<?> t = pollTask;
        if (t != null) {
            t.cancel(true);
            pollTask = null;
        }
        client = null;
    }

    /** Wird von Child-Handlern genutzt, um den gemeinsamen Client zu beziehen. */
    public @Nullable Client getClient() {
        return client;
    }

    public int getPollingIntervalSeconds() {
        return pollingIntervalSeconds;
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        String id = channelUID.getId();

        if (CHANNEL_PROGRAM_SELECTOR.equals(id)) {
            handleProgramCommand(command);
        } else if (CHANNEL_MANUAL_ZONE_SELECTOR.equals(id)) {
            handleManualZoneCommand(command);
        } else if (id.startsWith(CHANNEL_ZONE_ACTIVE_PREFIX)) {
            handleZoneActiveCommand(channelUID, command);
        } else if (id.startsWith(CHANNEL_ZONE_DURATION_PREFIX)) {
            handleZoneDurationCommand(channelUID, command);
        }
    }

    private void pollController() {
        Client activeClient = client;
        if (activeClient == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Client nicht initialisiert");
            return;
        }

        try {
            PollingResult result = activeClient.poll();
            handlePollingResult(result);
            refreshThingProperties(activeClient, result);
            updateStatus(ThingStatus.ONLINE);
        } catch (IOException e) {
            logger.debug("Rain Bird Polling fehlgeschlagen", e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    protected void handlePollingResult(PollingResult state) {
        ControllerStatus controllerStatus = state.getControllerStatus();
        NetworkStatus networkStatus = controllerStatus.getNetworkStatus();
        updateState(CHANNEL_NETWORK_UP, networkStatus.isNetworkUp() ? OnOffType.ON : OnOffType.OFF);
        updateState(CHANNEL_INTERNET_UP, networkStatus.isInternetUp() ? OnOffType.ON : OnOffType.OFF);

        WifiStatus wifiStatus = controllerStatus.getWifiStatus();
        updateState(CHANNEL_WIFI_SIGNAL, new DecimalType(wifiStatus.getRssi()));
        String ssid = wifiStatus.getSsid();
        updateState(CHANNEL_WIFI_SSID, ssid != null && !ssid.isEmpty() ? new StringType(ssid) : UnDefType.NULL);
        String mac = wifiStatus.getMacAddress();
        updateState(CHANNEL_WIFI_MAC, mac != null && !mac.isEmpty() ? new StringType(mac) : UnDefType.NULL);

        if ((deviceId == null || deviceId.isEmpty() || "controller".equals(deviceId)) && mac != null && !mac.isEmpty()) {
            deviceId = mac;
        }

        CombinedState combinedState = controllerStatus.getCombinedState();
        ZonedDateTime controllerTime = combinedState.getControllerTime().atZone(ZoneId.systemDefault());
        updateState(CHANNEL_CONTROLLER_TIME, new DateTimeType(controllerTime));
        updateState(CHANNEL_RAIN_DELAY, new DecimalType(combinedState.getDelaySetting()));
        updateState(CHANNEL_SEASONAL_ADJUST, new DecimalType(combinedState.getSeasonalAdjust()));

        ZoneStatus zoneStatus = state.getZoneStatus();
        int zoneCount = resolveZoneCount(zoneStatus);
        updateState(CHANNEL_ZONE_COUNT, new DecimalType(zoneCount));
        updateZoneChannels(zoneCount);
        updateZoneChannelStates(zoneStatus);
        updateState(CHANNEL_ACTIVE_STATION, new DecimalType(zoneStatus.getActiveZone()));

        ProgramStatus programStatus = state.getProgramStatus();
        updateState(CHANNEL_PROGRAM_COUNT, new DecimalType(programStatus.getProgramCount()));

        String summary = programStatus.getSummaries().isEmpty() ? ""
                : String.join("\n", programStatus.getSummaries());
        updateState(CHANNEL_SCHEDULE_SUMMARY, new StringType(summary));

        ZonedDateTime refreshedAt = controllerStatus.getRefreshedAt().atZone(ZoneId.systemDefault());
        updateState(CHANNEL_LAST_POLL, new DateTimeType(refreshedAt));
    }

    private void refreshThingProperties(Client activeClient, PollingResult state) {
        Map<String, String> properties = new HashMap<>();
        Map<String, String> current = getThing().getProperties();
        if (current != null) {
            properties.putAll(current);
        }
        boolean changed = false;

            ModelAndVersion model = ensureModelInfo(activeClient);
            if (model != null) {
                changed |= applyProperty(properties, PROPERTY_CONTROLLER_MODEL, model.getModelName());
            }

            ControllerFirmwareVersion firmware = ensureControllerFirmware(activeClient);
            if (firmware != null) {
                changed |= applyProperty(properties, PROPERTY_CONTROLLER_FIRMWARE, firmware.asVersionString());
            }

            WifiStatus wifiStatus = state.getControllerStatus().getWifiStatus();
            changed |= applyProperty(properties, PROPERTY_WIFI_FIRMWARE, wifiStatus.getFirmwareVersion());

            ZipCodeInfo zipCode = ensureZipCode(activeClient);
            String zipCodeValue = zipCode != null ? zipCode.getCode() : null;
            changed |= applyProperty(properties, PROPERTY_ZIP_CODE, zipCodeValue);
            String countryValue = zipCode != null ? zipCode.getCountry() : null;
            changed |= applyProperty(properties, PROPERTY_COUNTRY, countryValue);

            String customName = ensureCustomStationName(activeClient, zipCode);
            changed |= applyProperty(properties, PROPERTY_CUSTOM_STATION_NAME, customName);

        if (changed) {
            getThing().setProperties(properties);
        }
    }

    private @Nullable ModelAndVersion ensureModelInfo(Client activeClient) {
        ModelAndVersion model = cachedModel;
        if (model != null) {
            return model;
        }
        try {
            model = activeClient.getModelAndVersion();
            cachedModel = model;
            return model;
        } catch (IOException e) {
            logger.debug("Konnte das Rain-Bird-Modell nicht abrufen", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    private @Nullable ControllerFirmwareVersion ensureControllerFirmware(Client activeClient) {
        ControllerFirmwareVersion firmware = cachedControllerFirmware;
        if (firmware != null) {
            return firmware;
        }
        try {
            firmware = activeClient.getControllerFirmwareVersion();
            cachedControllerFirmware = firmware;
            return firmware;
        } catch (IOException e) {
            logger.debug("Konnte die Controller-Firmware nicht abrufen", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    private @Nullable ZipCodeInfo ensureZipCode(Client activeClient) {
        ZipCodeInfo info = cachedZipCode;
        if (info != null) {
            return info;
        }
        try {
            info = activeClient.getZipCode();
            cachedZipCode = info;
            return info;
        } catch (IOException e) {
            logger.debug("Konnte die Standortinformationen nicht abrufen", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    private @Nullable String ensureCustomStationName(Client activeClient, @Nullable ZipCodeInfo zipCode) {
        if (cachedCustomStationName != null && !cachedCustomStationName.isBlank()) {
            return cachedCustomStationName;
        }
        if (zipCode == null) {
            return null;
        }
        String stickId = deviceId;
        if (stickId == null || stickId.isBlank() || "controller".equalsIgnoreCase(stickId)) {
            return null;
        }
        String zip = zipCode.getCode();
        String country = zipCode.getCountry();
        if (zip == null || zip.isBlank() || country == null || country.isBlank()) {
            return null;
        }
        try {
            WeatherStatus weatherStatus = activeClient.getWeatherAndStatus(stickId, country, zip);
            if (weatherStatus == null) {
                return null;
            }
            String controllerName = weatherStatus.getControllerName();
            if (controllerName != null && !controllerName.isBlank()) {
                cachedCustomStationName = controllerName.trim();
                return cachedCustomStationName;
            }
            for (String value : weatherStatus.getCustomStationNames().values()) {
                if (value != null && !value.isBlank()) {
                    cachedCustomStationName = value.trim();
                    return cachedCustomStationName;
                }
            }
        } catch (IOException e) {
            logger.debug("Konnte den Stationsnamen nicht vom Cloud-Dienst laden", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return null;
    }

    private boolean applyProperty(Map<String, String> properties, String key, @Nullable String value) {
        if (value == null) {
            if (properties.containsKey(key)) {
                properties.remove(key);
                return true;
            }
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            if (properties.containsKey(key)) {
                properties.remove(key);
                return true;
            }
            return false;
        }
        String current = properties.get(key);
        if (trimmed.equals(current)) {
            return false;
        }
        properties.put(key, trimmed);
        return true;
    }

    private void handleProgramCommand(Command command) {
        if (!(command instanceof StringType)) {
            return;
        }

        Client activeClient = client;
        if (activeClient == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Client nicht verfügbar");
            return;
        }

        String value = ((StringType) command).toString().trim().toUpperCase();
        RainbirdCommandResult result;
        switch (value) {
            case "A":
                result = activeClient.runProgram(0);
                break;
            case "B":
                result = activeClient.runProgram(1);
                break;
            case "C":
                result = activeClient.runProgram(2);
                break;
            case "STOP":
                result = activeClient.stopAllZones();
                break;
            default:
                logger.debug("Unbekannter Programm-Befehl: {}", value);
                return;
        }

        if (!result.isSuccess()) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Programmbefehl fehlgeschlagen");
        }
    }

    private void handleManualZoneCommand(Command command) {
        if (!(command instanceof StringType)) {
            return;
        }

        Client activeClient = client;
        if (activeClient == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Client nicht verfügbar");
            return;
        }

        String value = ((StringType) command).toString().trim().toUpperCase();
        if (value.startsWith("ZONE") && value.endsWith("_ON")) {
            int zone = parseZoneNumber(value);
            if (zone > 0) {
                RainbirdCommandResult result = activeClient.runStation(zone, 5);
                if (!result.isSuccess()) {
                    updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                            "Zonenstart fehlgeschlagen");
                }
            }
        } else if (value.startsWith("ZONE") && value.endsWith("_OFF")) {
            RainbirdCommandResult result = activeClient.stopAllZones();
            if (!result.isSuccess()) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Zonenstopp fehlgeschlagen");
            }
        }
    }

    private int resolveZoneCount(ZoneStatus status) {
        int available = status.getAvailableZones().size();
        int slots = status.getSlotCount();

        if (available > 0) {
            // Bevorzuge wirklich verfügbare/konfigurierte Zonen
            return available;
        } else if (slots > 0) {
            // Fallback für ältere Controller, die keine Zonenliste liefern
            return slots;
        } else {
            return 0;
        }
    }

    private void handleZoneActiveCommand(ChannelUID channelUID, Command command) {
        Integer zoneIndex = parseZoneIndex(channelUID.getId(), CHANNEL_ZONE_ACTIVE_PREFIX);
        if (zoneIndex == null) {
            return;
        }

        if (!(command instanceof OnOffType)) {
            return;
        }

        Client activeClient = client;
        if (activeClient == null) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Client nicht verfügbar");
            return;
        }

        int zoneNumber = zoneIndex.intValue();
        if (command == OnOffType.ON) {
            int duration = zoneDurationsMinutes.getOrDefault(Integer.valueOf(zoneNumber), DEFAULT_MANUAL_DURATION_MINUTES);
            duration = sanitizeDurationMinutes(duration);
            zoneDurationsMinutes.put(Integer.valueOf(zoneNumber), Integer.valueOf(duration));

            int remainingSeconds = duration * 60;
            updateState(CHANNEL_ZONE_REMAINING_PREFIX + zoneNumber, new DecimalType(remainingSeconds));

            RainbirdCommandResult result = activeClient.runStation(zoneNumber, duration);
            if (!result.isSuccess()) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Zonenstart fehlgeschlagen");
            }
        } else if (command == OnOffType.OFF) {
            RainbirdCommandResult result = activeClient.stopAllZones();
            if (!result.isSuccess()) {
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Zonenstopp fehlgeschlagen");
            }

            int channelCount = lastDynamicZoneCount;
            for (int zone = 1; zone <= channelCount; zone++) {
                updateState(CHANNEL_ZONE_ACTIVE_PREFIX + zone, OnOffType.OFF);
                updateState(CHANNEL_ZONE_REMAINING_PREFIX + zone, new DecimalType(0));
            }
        }
    }

    private void handleZoneDurationCommand(ChannelUID channelUID, Command command) {
        Integer zoneIndex = parseZoneIndex(channelUID.getId(), CHANNEL_ZONE_DURATION_PREFIX);
        if (zoneIndex == null) {
            return;
        }

        int minutes = extractDurationMinutes(command);
        if (minutes <= 0) {
            return;
        }
        int sanitized = sanitizeDurationMinutes(minutes);
        zoneDurationsMinutes.put(zoneIndex, Integer.valueOf(sanitized));
        updateState(channelUID.getId(), new DecimalType(sanitized * 60));
    }

    private void publishDurationState(int zoneIndex) {
        Integer duration = zoneDurationsMinutes.get(Integer.valueOf(zoneIndex));
        if (duration == null) {
            duration = Integer.valueOf(DEFAULT_MANUAL_DURATION_MINUTES);
            zoneDurationsMinutes.put(Integer.valueOf(zoneIndex), duration);
            updateState(CHANNEL_ZONE_DURATION_PREFIX + zoneIndex, new DecimalType(duration.intValue() * 60));
        }
    }

    private synchronized void updateZoneChannels(int zoneCount) {
        if (zoneCount < 0) {
            zoneCount = 0;
        }

        final int targetCount = zoneCount;
        zoneDurationsMinutes.keySet().removeIf(index -> index.intValue() > targetCount);
        boolean needsUpdate = targetCount != lastDynamicZoneCount || hasMismatchedDynamicChannels(targetCount);
        if (needsUpdate) {
            if (rebuildDynamicZoneChannels(targetCount)) {
                lastDynamicZoneCount = targetCount;
            }
        } else {
            lastDynamicZoneCount = targetCount;
        }
        ensureDefaultZoneDurations(targetCount);
    }

    private void ensureDefaultZoneDurations(int zoneCount) {
        for (int i = 1; i <= zoneCount; i++) {
            publishDurationState(i);
        }
    }

    private boolean rebuildDynamicZoneChannels(int zoneCount) {
        Thing thing = getThing();
        ThingUID thingUID = thing.getUID();
        List<Channel> existingChannels = getExistingChannels(thing);
        List<Channel> updatedChannels = new ArrayList<>();
        for (Channel channel : existingChannels) {
            if (!isDynamicZoneChannel(channel.getUID().getId())) {
                updatedChannels.add(channel);
            }
        }

        for (int zone = 1; zone <= zoneCount; zone++) {
            updatedChannels.add(createZoneActiveChannel(thingUID, zone));
            updatedChannels.add(createZoneDurationChannel(thingUID, zone));
            updatedChannels.add(createZoneRemainingChannel(thingUID, zone));
        }

        ThingBuilder builder = editThing();
        builder.withChannels(updatedChannels);
        updateThing(builder.build());
        return true;
    }

    private boolean hasMismatchedDynamicChannels(int expectedZoneCount) {
        Thing thing = getThing();
        List<Channel> channels = getExistingChannels(thing);
        for (Channel channel : channels) {
            String channelId = channel.getUID().getId();
            if (channelId.startsWith(CHANNEL_ZONE_ACTIVE_PREFIX)) {
                if (!isValidZoneIndex(parseZoneIndex(channelId, CHANNEL_ZONE_ACTIVE_PREFIX), expectedZoneCount)) {
                    return true;
                }
            } else if (channelId.startsWith(CHANNEL_ZONE_DURATION_PREFIX)) {
                if (!isValidZoneIndex(parseZoneIndex(channelId, CHANNEL_ZONE_DURATION_PREFIX), expectedZoneCount)) {
                    return true;
                }
            } else if (channelId.startsWith(CHANNEL_ZONE_REMAINING_PREFIX)) {
                if (!isValidZoneIndex(parseZoneIndex(channelId, CHANNEL_ZONE_REMAINING_PREFIX), expectedZoneCount)) {
                    return true;
                }
            }
        }

        for (int zone = 1; zone <= expectedZoneCount; zone++) {
            if (!containsChannel(channels, CHANNEL_ZONE_ACTIVE_PREFIX + zone)
                    || !containsChannel(channels, CHANNEL_ZONE_DURATION_PREFIX + zone)
                    || !containsChannel(channels, CHANNEL_ZONE_REMAINING_PREFIX + zone)) {
                return true;
            }
        }

        return false;
    }

    private List<Channel> getExistingChannels(Thing thing) {
        List<Channel> channels = thing.getChannels();
        if (channels == null) {
            return Collections.emptyList();
        }
        return channels;
    }

    private boolean containsChannel(List<Channel> channels, String channelId) {
        for (Channel channel : channels) {
            if (channelId.equals(channel.getUID().getId())) {
                return true;
            }
        }
        return false;
    }

    private boolean isValidZoneIndex(@Nullable Integer zoneIndex, int expectedZoneCount) {
        if (zoneIndex == null) {
            return false;
        }
        int index = zoneIndex.intValue();
        if (index <= 0) {
            return false;
        }
        return index <= expectedZoneCount;
    }

    private boolean isDynamicZoneChannel(String channelId) {
        return channelId.startsWith(CHANNEL_ZONE_ACTIVE_PREFIX) || channelId.startsWith(CHANNEL_ZONE_DURATION_PREFIX)
                || channelId.startsWith(CHANNEL_ZONE_REMAINING_PREFIX);
    }

    private Channel createZoneActiveChannel(ThingUID thingUID, int zoneIndex) {
        return createZoneChannel(thingUID, CHANNEL_ZONE_ACTIVE_PREFIX + zoneIndex, CHANNEL_TYPE_ZONE_ACTIVE,
                "Zone " + zoneIndex + " Active");
    }

    private Channel createZoneDurationChannel(ThingUID thingUID, int zoneIndex) {
        return createZoneChannel(thingUID, CHANNEL_ZONE_DURATION_PREFIX + zoneIndex, CHANNEL_TYPE_ZONE_DURATION,
                "Zone " + zoneIndex + " Duration");
    }

    private Channel createZoneRemainingChannel(ThingUID thingUID, int zoneIndex) {
        return createZoneChannel(thingUID, CHANNEL_ZONE_REMAINING_PREFIX + zoneIndex, CHANNEL_TYPE_ZONE_REMAINING,
                "Zone " + zoneIndex + " Remaining");
    }

    private Channel createZoneChannel(ThingUID thingUID, String channelId, String channelTypeId, String label) {
        ChannelUID channelUID = new ChannelUID(thingUID, channelId);
        ChannelTypeUID channelTypeUID = new ChannelTypeUID(BINDING_ID, channelTypeId);

        String itemType;
        if (CHANNEL_TYPE_ZONE_ACTIVE.equals(channelTypeId)) {
            itemType = "Switch";
        } else {
            itemType = "Number";
        }

        return ChannelBuilder.create(channelUID, itemType).withType(channelTypeUID).withLabel(label).build();
    }

private void updateZoneChannelStates(ZoneStatus zoneStatus) {
        lastZoneStatus = zoneStatus;
        int channelCount = lastDynamicZoneCount;
        if (channelCount <= 0) {
            return;
        }

        int activeZone = zoneStatus.getActiveZone();
        int remainingSeconds = zoneStatus.getRemainingRuntime();
        for (int zone = 1; zone <= channelCount; zone++) {
            boolean active = zone == activeZone;
            updateState(CHANNEL_ZONE_ACTIVE_PREFIX + zone, active ? OnOffType.ON : OnOffType.OFF);
            int seconds = active ? remainingSeconds : 0;
            updateState(CHANNEL_ZONE_REMAINING_PREFIX + zone, new DecimalType(seconds));
        }
    }

    private int secondsToMinutesCeiling(int seconds) {
        if (seconds <= 0) {
            return 0;
        }
        return (int) Math.ceil(seconds / 60.0);
    }

    private QuantityType<?> minutesToQuantityType(int minutes) {
        int safe = Math.max(0, minutes);
        return new QuantityType<>(safe, Units.MINUTE);
    }

    private @Nullable Integer parseZoneIndex(String channelId, String prefix) {
        if (!channelId.startsWith(prefix)) {
            return null;
        }
        try {
            return Integer.valueOf(channelId.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int extractDurationMinutes(Command command) {
        if (command instanceof QuantityType<?>) {
            QuantityType<?> quantity = (QuantityType<?>) command;
            try {
                QuantityType<?> seconds = quantity.toUnit(Units.SECOND);
                return secondsToMinutesCeiling(seconds.intValue());
            } catch (IllegalArgumentException e) {
                return quantity.intValue();
            }
        }
        if (command instanceof DecimalType) {
            int seconds = ((DecimalType) command).toBigDecimal().intValue();
            return secondsToMinutesCeiling(seconds);
        }
        return -1;
    }

    private int sanitizeDurationMinutes(int minutes) {
        if (minutes <= 0) {
            return DEFAULT_MANUAL_DURATION_MINUTES;
        }
        return Math.max(1, Math.min(MAX_MANUAL_DURATION_MINUTES, minutes));
    }

    private int parseZoneNumber(String command) {
        int start = command.indexOf('E') + 1;
        int end = command.indexOf('_', start);
        if (start <= 0 || end <= start) {
            return -1;
        }
        try {
            return Integer.parseInt(command.substring(start, end));
        } catch (NumberFormatException e) {
            logger.debug("Konnte Zonennummer nicht parsen: {}", command, e);
            return -1;
        }
    }

    private RainbirdConfiguration createConfiguration(String hostConfig, int configuredPort,
            @Nullable String password, int pollingInterval, int timeout) {
        RainbirdConfiguration configuration = new RainbirdConfiguration();
        configuration.password = password != null ? password : "";
        configuration.pollingInterval = pollingInterval;
        configuration.timeoutMillis = Math.max(1000, timeout);

        HostSettings settings = parseHostConfiguration(hostConfig, configuredPort);
        configuration.host = settings.host;
        configuration.port = settings.port;
        configuration.basePath = settings.path;

        return configuration;
    }

    private RainbirdConfiguration createCloudConfiguration(int timeout) {
        RainbirdConfiguration cloud = new RainbirdConfiguration();
        cloud.host = "rdz-rbcloud.rainbird.com";
        cloud.basePath = "/phone-api";
        cloud.port = 80;
        cloud.timeoutMillis = Math.max(1000, timeout);
        cloud.password = "";
        return cloud;
    }

    private HostSettings parseHostConfiguration(String hostConfig, int configuredPort) {
        if (hostConfig == null) {
            throw new IllegalArgumentException("host fehlt");
        }

        String trimmed = hostConfig.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("host fehlt");
        }

        try {
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                return parseUriHost(URI.create(trimmed), configuredPort);
            }
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Ungültiger Host: " + hostConfig, e);
        }

        int slash = trimmed.indexOf('/');
        String hostPart = slash >= 0 ? trimmed.substring(0, slash) : trimmed;
        String pathPart = slash >= 0 ? trimmed.substring(slash) : "/stick";

        if (hostPart.isEmpty()) {
            throw new IllegalArgumentException("host fehlt");
        }

        URI hostUri;
        try {
            hostUri = URI.create("http://" + hostPart);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Ungültiger Host: " + hostPart, e);
        }

        String host = hostUri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("host fehlt");
        }

        int port = hostUri.getPort();
        if (port <= 0) {
            port = configuredPort > 0 ? configuredPort : 80;
        }

        return new HostSettings(host, normalizePath(pathPart), port);
    }

    private HostSettings parseUriHost(URI uri, int configuredPort) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host fehlt");
        }

        String scheme = uri.getScheme();
        if (scheme != null && !scheme.isBlank() && !"http".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Nur HTTP-Verbindungen werden unterstützt");
        }
        int port = uri.getPort();
        if (port <= 0) {
            port = configuredPort > 0 ? configuredPort : 80;
        }

        String path = uri.getPath();
        return new HostSettings(host, normalizePath(path), port);
    }

    private String normalizePath(@Nullable String path) {
        String effective = path == null || path.isBlank() ? "/stick" : path.trim();
        if ("/".equals(effective)) {
            effective = "/stick";
        }
        return effective.startsWith("/") ? effective : "/" + effective;
    }

    private static final class HostSettings {
        final String host;
        final String path;
        final int port;

        HostSettings(String host, String path, int port) {
            this.host = host;
            this.path = path;
            this.port = port;
        }
    }

    public static class Client {
        private final RainbirdClient localClient;
        private final @Nullable RainbirdClient cloudClient;
        private volatile @Nullable PollingResult lastResult;

        Client(RainbirdClient localClient, @Nullable RainbirdClient cloudClient) {
            this.localClient = localClient;
            this.cloudClient = cloudClient;
        }

        public PollingResult poll() throws IOException, InterruptedException {
            PollingResult result = localClient.poll();
            lastResult = result;
            return result;
        }

        public @Nullable PollingResult getLastResult() {
            return lastResult;
        }

        public RainbirdCommandResult runProgram(int programIndex) {
            return localClient.runProgram(programIndex);
        }

        public RainbirdCommandResult stopAllZones() {
            return localClient.stopAllZones();
        }

        public RainbirdCommandResult runStation(int zoneNumber, int durationMinutes) {
            return localClient.runStation(zoneNumber, durationMinutes);
        }

        public ModelAndVersion getModelAndVersion() throws IOException, InterruptedException {
            return localClient.getModelAndVersion();
        }

        public ControllerFirmwareVersion getControllerFirmwareVersion() throws IOException, InterruptedException {
            return localClient.getControllerFirmwareVersion();
        }

        public ZipCodeInfo getZipCode() throws IOException, InterruptedException {
            return localClient.getZipCode();
        }

        public @Nullable WeatherStatus getWeatherAndStatus(String stickId, String country, String zipCode)
                throws IOException, InterruptedException {
            RainbirdClient cloud = cloudClient;
            if (cloud == null) {
                return null;
            }
            return cloud.getWeatherAndStatus(stickId, country, zipCode);
        }
    }
}