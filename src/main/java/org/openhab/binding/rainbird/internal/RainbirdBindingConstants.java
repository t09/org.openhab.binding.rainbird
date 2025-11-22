package org.openhab.binding.rainbird.internal;
import org.openhab.core.thing.ThingTypeUID;

/**
 * Binding-weite Konstanten.
 */
public final class RainbirdBindingConstants {

    private RainbirdBindingConstants() {}

    // -----------------------------------------------------------------------
    // IDs
    // -----------------------------------------------------------------------
    public static final String BINDING_ID = "rainbird";

    // Bridge + Things
    public static final String BRIDGE_ID = "bridge";
    public static final String THING_ID_ZONE = "zone";

    // ThingTypeUIDs
    public static final ThingTypeUID BRIDGE_TYPE_UID = new ThingTypeUID(BINDING_ID, BRIDGE_ID);
    public static final ThingTypeUID ZONE_TYPE_UID = new ThingTypeUID(BINDING_ID, THING_ID_ZONE);

    // Channel IDs (müssen zu channel-types.xml passen)
    public static final String CHANNEL_NETWORK_UP = "networkUp";
    public static final String CHANNEL_INTERNET_UP = "internetUp";
    public static final String CHANNEL_WIFI_SIGNAL = "wifiSignal";
    public static final String CHANNEL_WIFI_SSID = "wifiSsid";
    public static final String CHANNEL_WIFI_MAC = "wifiMac";
    public static final String CHANNEL_ZONE_COUNT = "zoneCount";
    public static final String CHANNEL_PROGRAM_COUNT = "programCount";
    public static final String CHANNEL_SCHEDULE_SUMMARY = "scheduleSummary";
    public static final String CHANNEL_PROGRAM_SELECTOR = "programSelector";
    public static final String CHANNEL_MANUAL_ZONE_SELECTOR = "manualZoneSelector";
    public static final String CHANNEL_CONTROLLER_TIME = "controllerTime";
    public static final String CHANNEL_RAIN_DELAY = "rainDelay";
    public static final String CHANNEL_SEASONAL_ADJUST = "seasonalAdjust";
    public static final String CHANNEL_ACTIVE_STATION = "activeStation";
    public static final String CHANNEL_LAST_POLL = "lastPoll";

    public static final String CHANNEL_ZONE_SWITCH = "switch";
    public static final String CHANNEL_ZONE_REMAINING_TIME = "remainingTime";
    public static final String CHANNEL_ZONE_FLOW = "flow";

    // Dynamic zone channel prefixes (bridge channels)
    public static final String CHANNEL_ZONE_ACTIVE_PREFIX = "zoneActive";
    public static final String CHANNEL_ZONE_DURATION_PREFIX = "zoneDuration";
    public static final String CHANNEL_ZONE_REMAINING_PREFIX = "zoneRemaining";

    public static final String CHANNEL_TYPE_ZONE_ACTIVE = "zoneActive";
    public static final String CHANNEL_TYPE_ZONE_DURATION = "zoneDuration";
    public static final String CHANNEL_TYPE_ZONE_REMAINING = "zoneRemaining";

    // Config-Parameter (müssen zu thing-types.xml passen)
    public static final String CONFIG_HOST = "host";
    public static final String CONFIG_PORT = "port";
    public static final String CONFIG_PASSWORD = "password";
    public static final String CONFIG_REFRESH = "refresh";
    public static final String CONFIG_TIMEOUT = "timeout";

    public static final String CONFIG_DEVICE_ID = "deviceId";
    public static final String CONFIG_ZONE_NUMBER = "zoneNumber";
    public static final String CONFIG_DEFAULT_DURATION = "defaultDuration";

    // Thing property names
    public static final String PROPERTY_CONTROLLER_MODEL = "controller.model";
    public static final String PROPERTY_CONTROLLER_FIRMWARE = "controller.firmware";
    public static final String PROPERTY_WIFI_FIRMWARE = "wifi.firmware";
    public static final String PROPERTY_CUSTOM_STATION_NAME = "controller.customStationName";
    public static final String PROPERTY_ZIP_CODE = "controller.zipCode";
    public static final String PROPERTY_COUNTRY = "controller.country";
}
