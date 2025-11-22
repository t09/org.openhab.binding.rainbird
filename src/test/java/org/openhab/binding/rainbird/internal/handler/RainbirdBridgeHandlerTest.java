package org.openhab.binding.rainbird.internal.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.openhab.binding.rainbird.internal.RainbirdBindingConstants;
import org.openhab.binding.rainbird.internal.net.RainbirdClient.CombinedState;
import org.openhab.binding.rainbird.internal.net.RainbirdClient.ControllerStatus;
import org.openhab.binding.rainbird.internal.net.RainbirdClient.NetworkStatus;
import org.openhab.binding.rainbird.internal.net.RainbirdClient.PollingResult;
import org.openhab.binding.rainbird.internal.net.RainbirdClient.ProgramStatus;
import org.openhab.binding.rainbird.internal.net.RainbirdClient.WifiStatus;
import org.openhab.binding.rainbird.internal.net.RainbirdClient.ZoneStatus;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.StringType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.types.State;
import org.mockito.Mockito;

/**
 * Verifies that the bridge handler maps decoded controller state onto channels.
 */
@Disabled("Units bootstrap not available in plain Maven test; behavior is tested in real OH runtime")
class RainbirdBridgeHandlerTest {

    @Test
    void handlePollingResultEmitsChannelValues() {
        Bridge bridge = Mockito.mock(Bridge.class);
        ThingUID thingUID = new ThingUID("rainbird:bridge:1");
        Mockito.when(bridge.getUID()).thenReturn(thingUID);
        RecordingBridgeHandler handler = new RecordingBridgeHandler(bridge);
        WifiStatus wifiStatus = new WifiStatus(-59, "GardenWiFi", "AA:BB:CC:DD:EE:FF", "3.15");
        NetworkStatus networkStatus = new NetworkStatus(true, true);
        CombinedState combined = new CombinedState(2, 0, 0, 110, 0, 3, LocalDateTime.of(2024, 1, 1, 6, 15));
        ControllerStatus controller = new ControllerStatus(networkStatus, wifiStatus, combined,
                Instant.parse("2024-01-02T12:00:00Z"));
        ProgramStatus programs = new ProgramStatus(3, List.of("Program A: Starts 04:00; Zones 1=25m"));
        ZoneStatus zones = new ZoneStatus(Set.of(1, 2, 3, 4, 5, 6), 6, 3, 0);
        PollingResult state = new PollingResult(controller, programs, zones);

        handler.handlePollingResult(state);

        Map<String, State> recorded = handler.getRecordedStates();
        assertEquals(OnOffType.ON, recorded.get(RainbirdBindingConstants.CHANNEL_NETWORK_UP));
        assertEquals(OnOffType.ON, recorded.get(RainbirdBindingConstants.CHANNEL_INTERNET_UP));
        assertEquals(new DecimalType(-59), recorded.get(RainbirdBindingConstants.CHANNEL_WIFI_SIGNAL));
        assertEquals(new StringType("GardenWiFi"), recorded.get(RainbirdBindingConstants.CHANNEL_WIFI_SSID));
        assertEquals(new StringType("AA:BB:CC:DD:EE:FF"), recorded.get(RainbirdBindingConstants.CHANNEL_WIFI_MAC));
        assertEquals(new DecimalType(6), recorded.get(RainbirdBindingConstants.CHANNEL_ZONE_COUNT));
        assertEquals(new DecimalType(3), recorded.get(RainbirdBindingConstants.CHANNEL_PROGRAM_COUNT));
        assertEquals(new StringType("Program A: Starts 04:00; Zones 1=25m"),
                recorded.get(RainbirdBindingConstants.CHANNEL_SCHEDULE_SUMMARY));
        ZonedDateTime expectedControllerTime = LocalDateTime.of(2024, 1, 1, 6, 15).atZone(ZoneId.systemDefault());
        assertEquals(new DateTimeType(expectedControllerTime),
                recorded.get(RainbirdBindingConstants.CHANNEL_CONTROLLER_TIME));
        assertEquals(new DecimalType(2), recorded.get(RainbirdBindingConstants.CHANNEL_RAIN_DELAY));
        assertEquals(new DecimalType(110),
                recorded.get(RainbirdBindingConstants.CHANNEL_SEASONAL_ADJUST));
        assertEquals(new DecimalType(3), recorded.get(RainbirdBindingConstants.CHANNEL_ACTIVE_STATION));
        ZonedDateTime expectedRefreshedAt = Instant.parse("2024-01-02T12:00:00Z").atZone(ZoneId.systemDefault());
        assertEquals(new DateTimeType(expectedRefreshedAt),
                recorded.get(RainbirdBindingConstants.CHANNEL_LAST_POLL));
    }

    private static final class RecordingBridgeHandler extends RainbirdBridgeHandler {

        private final Map<String, State> recorded = new java.util.HashMap<>();

        RecordingBridgeHandler(Bridge bridge) {
            super(bridge);
        }

        @Override
        protected void updateState(String channelId, State state) {
            recorded.put(channelId, state);
        }

        @Override
        protected void updateStatus(ThingStatus status) {
            // Avoid interactions with the Thing registry during tests.
        }

        Map<String, State> getRecordedStates() {
            return recorded;
        }
    }
}