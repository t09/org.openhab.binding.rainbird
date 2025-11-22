package org.openhab.binding.rainbird.internal.net;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.rainbird.internal.config.RainbirdConfiguration;
import org.openhab.binding.rainbird.internal.util.ModelInfoRegistry;
import org.openhab.binding.rainbird.internal.util.ModelInfoRegistry.ModelInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client implementation that communicates with a Rain Bird controller via the encrypted stick protocol.
 */
@NonNullByDefault
public class RainbirdClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(RainbirdClient.class);
    private static final Map<String, String> RAINBIRD_APP_HEADERS;

    static {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept-Language", "en");
        headers.put("Accept-Encoding", "gzip, deflate");
        headers.put("User-Agent", "RainBird/2.0 CFNetwork/811.5.4 Darwin/16.7.0");
        headers.put("Accept", "*/*");
        headers.put("Content-Type", "application/octet-stream");
        RAINBIRD_APP_HEADERS = Collections.unmodifiableMap(headers);
    }

    private final RainbirdPayloadCoder coder;
    private final URI endpoint;
    private final Duration requestTimeout;
    private final AtomicLong requestId = new AtomicLong();

    public RainbirdClient(RainbirdConfiguration configuration) {
        this.coder = new RainbirdPayloadCoder(configuration.password);
        this.requestTimeout = resolveTimeout(configuration);
        this.endpoint = buildEndpoint(configuration);
    }

    private static Duration resolveTimeout(RainbirdConfiguration configuration) {
        int timeout = configuration.timeoutMillis > 0 ? configuration.timeoutMillis : 5000;
        return Duration.ofMillis(Math.max(1000, timeout));
    }

    private static URI buildEndpoint(RainbirdConfiguration configuration) {
        String host = configuration.host;
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Rain Bird host is not configured");
        }

        String path = configuration.basePath;
        if (path == null || path.isBlank()) {
            path = "/stick";
        }
        path = path.trim();
        if (!path.startsWith("/")) {
            path = "/" + path;
        }

        try {
            int port = configuration.port;
            if (port <= 0) {
                port = -1;
            }
            return new URI("http", null, host.trim(), port, path, null, null);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid Rain Bird endpoint", e);
        }
    }

    /**
     * Poll the controller for its current status, programs and zone state.
     */
    public PollingResult poll() throws IOException, InterruptedException {
        Map<String, Object> networkPayload = invoke("getNetworkStatus", Map.of());
        NetworkStatus networkStatus = new NetworkStatus(asBoolean(networkPayload.get("networkUp")),
                asBoolean(networkPayload.get("internetUp")));

        Map<String, Object> wifiPayload = invoke("getWifiParams", Map.of());
        WifiStatus wifiStatus = decodeWifiStatus(wifiPayload);

        Map<String, Object> settingsPayload = invoke("getSettings", Map.of());
        int programCount = asInt(settingsPayload.get("numPrograms"), 0);

        AvailableStationsData stations = sendCommand(StickCommand.AVAILABLE_STATIONS, RainbirdClient::decodeAvailableStations,
                Integer.valueOf(0));
        CombinedState combinedState = sendCommand(StickCommand.COMBINED_CONTROLLER_STATE,
                RainbirdClient::decodeCombinedControllerState);

        List<String> scheduleSummaries = fetchScheduleSummaries(programCount, stations);

        ControllerStatus controllerStatus = new ControllerStatus(networkStatus, wifiStatus, combinedState, Instant.now());
        ProgramStatus programStatus = new ProgramStatus(programCount, scheduleSummaries);
        ZoneStatus zoneStatus = new ZoneStatus(stations.activeZones(), stations.slotCount(), combinedState.getActiveStation(),
                combinedState.getRemainingRuntime());

        return new PollingResult(controllerStatus, programStatus, zoneStatus);
    }

    /**
     * Retrieve the controller model and protocol version.
     */
    public ModelAndVersion getModelAndVersion() throws IOException, InterruptedException {
        return sendCommand(StickCommand.MODEL_AND_VERSION, RainbirdClient::decodeModelAndVersion);
    }

    /**
     * Retrieve the controller firmware version.
     */
    public ControllerFirmwareVersion getControllerFirmwareVersion() throws IOException, InterruptedException {
        return sendCommand(StickCommand.CONTROLLER_FIRMWARE_VERSION,
                RainbirdClient::decodeControllerFirmwareVersion);
    }

    /**
     * Retrieve the configured zip code and country.
     */
    public ZipCodeInfo getZipCode() throws IOException, InterruptedException {
        Map<String, Object> response = invoke("getZipCode", Map.of());
        return decodeZipCode(response);
    }

    /**
     * Retrieve controller metadata from the Rain Bird cloud.
     */
    public WeatherStatus getWeatherAndStatus(String stickId, String country, String zipCode)
            throws IOException, InterruptedException {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("StickId", stickId);
        params.put("Country", country);
        params.put("ZipCode", zipCode);
        Map<String, Object> response = invoke("requestWeatherAndStatus", params);
        return decodeWeatherStatus(response);
    }

    /**
     * Run a stored irrigation program.
     */
    public RainbirdCommandResult runProgram(int programIndex) {
        if (programIndex < 0 || programIndex > 255) {
            return failureResult(StickCommand.MANUALLY_RUN_PROGRAM);
        }
        try {
            return sendCommand(StickCommand.MANUALLY_RUN_PROGRAM, RainbirdClient::decodeCommandResult,
                    Integer.valueOf(programIndex));
        } catch (IOException e) {
            LOGGER.warn("Error starting program {}", Integer.valueOf(programIndex), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return failureResult(StickCommand.MANUALLY_RUN_PROGRAM);
    }

    /**
     * Run a single irrigation zone for the supplied number of minutes.
     */
    public RainbirdCommandResult runStation(int zone, int durationMinutes) {
        if (zone <= 0) {
            return failureResult(StickCommand.MANUALLY_RUN_STATION);
        }
        int safeZone = Math.max(0, Math.min(65535, zone));
        int safeDuration = Math.max(1, Math.min(255, durationMinutes));
        try {
            return sendCommand(StickCommand.MANUALLY_RUN_STATION, RainbirdClient::decodeCommandResult,
                    Integer.valueOf(safeZone), Integer.valueOf(safeDuration));
        } catch (IOException e) {
            LOGGER.warn("Error starting zone {}", Integer.valueOf(zone), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return failureResult(StickCommand.MANUALLY_RUN_STATION);
    }

    /**
     * Run each irrigation zone in sequence for the supplied duration.
     */
    public RainbirdCommandResult runZones(List<Integer> zones, int durationMinutes) {
        RainbirdCommandResult result = failureResult(StickCommand.MANUALLY_RUN_STATION);
        for (int zone : zones) {
            if (zone <= 0) {
                continue;
            }
            result = runStation(zone, durationMinutes);
            if (!result.isSuccess()) {
                return result;
            }
        }
        return result;
    }

    /**
     * Stop all irrigation immediately.
     */
    public RainbirdCommandResult stopAllZones() {
        try {
            return sendCommand(StickCommand.STOP_IRRIGATION, RainbirdClient::decodeCommandResult);
        } catch (IOException e) {
            LOGGER.warn("Error stopping irrigation", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return failureResult(StickCommand.STOP_IRRIGATION);
    }

    protected Map<String, Object> invoke(String method, Map<String, Object> params)
            throws IOException, InterruptedException {
        Map<String, Object> payload = RainbirdPayloadCoder.requestPayload(nextRequestId(), method,
                new LinkedHashMap<>(params));
        byte[] body = coder.encode(payload);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Sending Rain Bird request '{}' to {} with payload {}", method, endpoint, payload);
        }
        byte[] responseBody = sendRequest(body);
        Map<String, Object> envelope = coder.decode(responseBody);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("Rain Bird response for '{}' from {}: {}", method, endpoint, envelope);
        }
        Object error = envelope.get("error");
        if (error instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> errorMap = (Map<String, Object>) error;
            throw new IOException("Rain Bird responded with an error: " + errorMap.get("message"));
        }
        Object result = envelope.get("result");
        if (!(result instanceof Map)) {
            throw new IOException("Unexpected Rain Bird response payload");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> responseMap = (Map<String, Object>) result;
        return responseMap;
    }

    private byte[] sendRequest(byte[] body) throws IOException {
        HttpURLConnection connection = openConnection();
        try {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Rain Bird HTTP POST {} ({} bytes)", connection.getURL(), Integer.valueOf(body.length));
            }
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int status = connection.getResponseCode();
            byte[] response = readResponse(connection, status);
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debug("Rain Bird HTTP response {} {} from {}:\n{}",
                        Integer.valueOf(status), connection.getResponseMessage(), connection.getURL(),
                        toHexDump(response));
            }
            if (status >= 400) {
                throw new IOException("Unexpected HTTP status " + status + " from Rain Bird controller");
            }
            return response;
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection openConnection() throws IOException {
        URL url = endpoint.toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        int timeout = toTimeoutMillis(requestTimeout);
        connection.setConnectTimeout(timeout);
        connection.setReadTimeout(timeout);
        connection.setInstanceFollowRedirects(false);
        connection.setDoOutput(true);
        connection.setDoInput(true);
        connection.setRequestMethod("POST");
        connection.setUseCaches(false);
        RAINBIRD_APP_HEADERS.forEach(connection::setRequestProperty);
        return connection;
    }

    private static int toTimeoutMillis(Duration timeout) {
        long millis = timeout.toMillis();
        if (millis > Integer.MAX_VALUE) {
            return Integer.MAX_VALUE;
        }
        if (millis < 0) {
            return 0;
        }
        return (int) millis;
    }

    private byte[] readResponse(HttpURLConnection connection, int status) throws IOException {
        InputStream responseStream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (responseStream == null) {
            return new byte[0];
        }
        try (InputStream stream = wrapContentStream(responseStream, connection.getContentEncoding())) {
            return stream.readAllBytes();
        }
    }

    private InputStream wrapContentStream(InputStream stream, @Nullable String encoding) throws IOException {
        if (encoding == null) {
            return stream;
        }
        String normalized = encoding.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("gzip")) {
            return new GZIPInputStream(stream);
        }
        if (normalized.contains("deflate")) {
            return new InflaterInputStream(stream);
        }
        return stream;
    }

    private <T> T sendCommand(StickCommand command, SipDecoder<T> decoder, Object... args)
            throws IOException, InterruptedException {
        String payload = command.encode(args);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("data", payload);
        params.put("length", Integer.valueOf(command.length));
        Map<String, Object> response = invoke("tunnelSip", params);
        Object encoded = response.get("data");
        if (!(encoded instanceof String)) {
            throw new IOException("Rain Bird tunnel response missing data field");
        }
        String data = (String) encoded;
        if (data.length() < 2) {
            throw new IOException("Rain Bird tunnel response malformed");
        }
        return decoder.decode(command, data);
    }

    private static WifiStatus decodeWifiStatus(Map<String, Object> wifiParams) {
        int rssi = asInt(wifiParams.get("rssi"), 0);
        String ssid = asString(wifiParams.get("wifiSsid"));
        String mac = asString(wifiParams.get("macAddress"));
        String firmware = asString(wifiParams.get("stickVersion"));
        return new WifiStatus(rssi, ssid, mac, firmware);
    }

    private static AvailableStationsData decodeAvailableStations(StickCommand command, String data) throws IOException {
        expectPrefix(command, data, "83");
        if (data.length() < 12) {
            return new AvailableStationsData(Set.of(), 0);
        }
        int page = safeParseHex(data, 2, 2);
        String mask = data.substring(4);
        Set<Integer> active = new HashSet<>();
        int position = page * 8;
        for (int i = 0; i + 2 <= mask.length(); i += 2) {
            int current = safeParseHex(mask, i, 2);
            for (int bit = 0; bit < 8; bit++) {
                if ((current & (1 << bit)) != 0) {
                    active.add(position + bit + 1);
                }
            }
            position += 8;
        }
        int slotCount = mask.length() * 4;
        return new AvailableStationsData(active, slotCount);
    }

    private static ModelAndVersion decodeModelAndVersion(StickCommand command, String data) throws IOException {
        expectPrefix(command, data, "82");
        if (data.length() < 10) {
            throw new IOException("Model and version response too short");
        }
        int modelId = parseHex(data, 2, 4);
        int major = parseHex(data, 6, 2);
        int minor = parseHex(data, 8, 2);
        return new ModelAndVersion(modelId, major, minor);
    }

    private static CombinedState decodeCombinedControllerState(StickCommand command, String data) throws IOException {
        expectPrefix(command, data, "CC");
        if (data.length() < 32) {
            throw new IOException("Combined controller state response too short");
        }
        int hour = parseHex(data, 2, 2);
        int minute = parseHex(data, 4, 2);
        int second = parseHex(data, 6, 2);
        int day = parseHex(data, 8, 2);
        int month = parseHex(data, 10, 1);
        int year = parseHex(data, 11, 3);
        int delaySetting = parseHex(data, 14, 4);
        int sensorState = parseHex(data, 18, 2);
        int irrigationState = parseHex(data, 20, 2);
        int seasonalAdjust = parseHex(data, 22, 4);
        int remainingRuntime = parseHex(data, 26, 4);
        int activeStation = parseHex(data, 30, 2);
        LocalDateTime controllerTime = safeControllerTime(year, month, day, hour, minute, second);
        return new CombinedState(delaySetting, sensorState, irrigationState, seasonalAdjust, remainingRuntime, activeStation,
                controllerTime);
    }

    private static ControllerFirmwareVersion decodeControllerFirmwareVersion(StickCommand command, String data)
            throws IOException {
        expectPrefix(command, data, "8B");
        if (data.length() < 10) {
            throw new IOException("Controller firmware response too short");
        }
        int major = parseHex(data, 2, 2);
        int minor = parseHex(data, 4, 2);
        int patch = parseHex(data, 6, 4);
        return new ControllerFirmwareVersion(major, minor, patch);
    }

    private static RainbirdCommandResult decodeCommandResult(StickCommand command, String data) throws IOException {
        if (data.length() < 4) {
            LOGGER.debug("Truncated acknowledgement {} for {}", data, command);
            return failureResult(command);
        }
        String prefix = data.substring(0, 2);
        if ("00".equals(prefix)) {
            int echo = parseHex(data, 2, 2);
            int nak = parseHex(data, 4, 2);
            return new RainbirdCommandResult(false, echo, Integer.valueOf(nak));
        }
        if ("01".equals(prefix)) {
            int echo = parseHex(data, 2, 2);
            return new RainbirdCommandResult(true, echo, null);
        }
        LOGGER.debug("Unexpected acknowledgement {} for {}", data, command);
        return failureResult(command);
    }

    private static String decodeScheduleSegment(StickCommand command, String data) throws IOException {
        if (data.startsWith("00")) {
            throw new IOException("Rain Bird command was rejected for " + command.name());
        }
        expectPrefix(command, data, "A0");
        return data;
    }

    private static ZipCodeInfo decodeZipCode(Map<String, Object> response) {
        String country = asString(response.get("country"));
        String code = asString(response.get("code"));
        return new ZipCodeInfo(code, country);
    }

    private static WeatherStatus decodeWeatherStatus(Map<String, Object> response) {
        String stickId = asString(response.get("StickId"));
        @Nullable String controllerName = null;
        Map<Integer, String> stationNames = new LinkedHashMap<>();
        Object controllerObject = response.get("Controller");
        if (controllerObject instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> controller = (Map<String, Object>) controllerObject;
            controllerName = asString(controller.get("custom_name"));
            if (controllerName == null) {
                controllerName = asString(controller.get("customName"));
            }
            Object namesObject = controller.get("customStationNames");
            if (namesObject instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<Object, Object> names = (Map<Object, Object>) namesObject;
                for (Map.Entry<Object, Object> entry : names.entrySet()) {
                    String key = asString(entry.getKey());
                    String value = asString(entry.getValue());
                    if (key == null || value == null) {
                        continue;
                    }
                    try {
                        int zone = Integer.parseInt(key);
                        stationNames.put(Integer.valueOf(zone), value);
                    } catch (NumberFormatException e) {
                        LOGGER.debug("Ignoring invalid custom station key {}", key, e);
                    }
                }
            }
        }
        return new WeatherStatus(stickId, controllerName, stationNames);
    }

    private List<String> fetchScheduleSummaries(int programCount, AvailableStationsData stations)
            throws IOException, InterruptedException {
        Set<Integer> activeZones = stations.activeZones();
        List<String> responses = new ArrayList<>();
        responses.add(sendCommand(StickCommand.RETRIEVE_SCHEDULE, RainbirdClient::decodeScheduleSegment, Integer.valueOf(0)));
        for (int program = 0; program < programCount; program++) {
            responses.add(sendCommand(StickCommand.RETRIEVE_SCHEDULE, RainbirdClient::decodeScheduleSegment,
                    Integer.valueOf(0x10 | program)));
        }
        for (int program = 0; program < programCount; program++) {
            responses.add(sendCommand(StickCommand.RETRIEVE_SCHEDULE, RainbirdClient::decodeScheduleSegment,
                    Integer.valueOf(0x60 | program)));
        }
        int highestActive = activeZones.stream().mapToInt(Integer::intValue).max().orElse(0);
        int slotCount = stations.slotCount();
        int zoneLimit = highestActive > 0 ? highestActive : Math.min(slotCount, 22);
        int pages = (zoneLimit + 1) / 2;
        for (int page = 0; page < pages; page++) {
            responses.add(sendCommand(StickCommand.RETRIEVE_SCHEDULE, RainbirdClient::decodeScheduleSegment,
                    Integer.valueOf(0x80 | page)));
        }
        RainbirdScheduleParser parser = new RainbirdScheduleParser(programCount, activeZones);
        for (String response : responses) {
            parser.accept(response);
        }
        return parser.buildSummaries();
    }

    private long nextRequestId() {
        return requestId.incrementAndGet();
    }

    private static int asInt(@Nullable Object value, int defaultValue) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return defaultValue;
    }

    private static boolean asBoolean(@Nullable Object value) {
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        } else if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        } else if (value instanceof String) {
            String text = ((String) value).trim();
            if (text.isEmpty()) {
                return false;
            }
            // "true", "1" -> true
            return "true".equalsIgnoreCase(text) || "1".equals(text);
        }
        return false;
    }

    private static @Nullable String asString(@Nullable Object value) {
        if (value instanceof String) {
            String text = ((String) value).trim();
            return text.isEmpty() ? null : text;
        }
        return null;
    }

    private static int parseHex(String data, int position, int length) throws IOException {
        int end = position + length;
        if (position < 0 || end > data.length()) {
            throw new IOException("Rain Bird tunnel response truncated");
        }
        return Integer.parseInt(data.substring(position, end), 16);
    }

    private static int safeParseHex(String data, int position, int length) {
        try {
            return parseHex(data, position, length);
        } catch (IOException | NumberFormatException e) {
            LOGGER.debug("Unable to parse field at {} with length {} from {}", Integer.valueOf(position),
                    Integer.valueOf(length), data, e);
            return 0;
        }
    }

    private static LocalDateTime safeControllerTime(int year, int month, int day, int hour, int minute, int second) {
        try {
            int safeMonth = Math.max(1, Math.min(month, 12));
            int safeDay = Math.max(1, Math.min(day, 31));
            int safeHour = Math.max(0, Math.min(hour, 23));
            int safeMinute = Math.max(0, Math.min(minute, 59));
            int safeSecond = Math.max(0, Math.min(second, 59));
            return LocalDateTime.of(year, safeMonth, safeDay, safeHour, safeMinute, safeSecond);
        } catch (DateTimeException e) {
            LOGGER.debug("Invalid controller time {}/{}/{} {}:{}:{}", Integer.valueOf(month), Integer.valueOf(day),
                    Integer.valueOf(year), Integer.valueOf(hour), Integer.valueOf(minute), Integer.valueOf(second), e);
            return LocalDateTime.of(1970, 1, 1, 0, 0);
        }
    }

    private static RainbirdCommandResult failureResult(StickCommand command) {
        return new RainbirdCommandResult(false, command.commandEcho(), null);
    }

    private static void expectPrefix(StickCommand command, String data, String prefix) throws IOException {
        if (!data.startsWith(prefix)) {
            if (data.startsWith("00")) {
                throw new IOException("Rain Bird command was rejected for " + command.name());
            }
            throw new IOException("Unexpected response " + data + " for command " + command.name());
        }
    }

    /**
     * Strongly typed view of the current controller, program and zone data.
     */
    public static final class PollingResult {

        private final ControllerStatus controllerStatus;
        private final ProgramStatus programStatus;
        private final ZoneStatus zoneStatus;

        public PollingResult(ControllerStatus controllerStatus, ProgramStatus programStatus, ZoneStatus zoneStatus) {
            this.controllerStatus = Objects.requireNonNull(controllerStatus);
            this.programStatus = Objects.requireNonNull(programStatus);
            this.zoneStatus = Objects.requireNonNull(zoneStatus);
        }

        public ControllerStatus getControllerStatus() {
            return controllerStatus;
        }

        public ProgramStatus getProgramStatus() {
            return programStatus;
        }

        public ZoneStatus getZoneStatus() {
            return zoneStatus;
        }
    }

    public static final class ControllerStatus {

        private final NetworkStatus networkStatus;
        private final WifiStatus wifiStatus;
        private final CombinedState combinedState;
        private final Instant refreshedAt;

        public ControllerStatus(NetworkStatus networkStatus, WifiStatus wifiStatus, CombinedState combinedState,
                Instant refreshedAt) {
            this.networkStatus = Objects.requireNonNull(networkStatus);
            this.wifiStatus = Objects.requireNonNull(wifiStatus);
            this.combinedState = Objects.requireNonNull(combinedState);
            this.refreshedAt = Objects.requireNonNull(refreshedAt);
        }

        public NetworkStatus getNetworkStatus() {
            return networkStatus;
        }

        public WifiStatus getWifiStatus() {
            return wifiStatus;
        }

        public CombinedState getCombinedState() {
            return combinedState;
        }

        public Instant getRefreshedAt() {
            return refreshedAt;
        }
    }

    public static final class ProgramStatus {

        private final int programCount;
        private final List<String> summaries;

        public ProgramStatus(int programCount, List<String> summaries) {
            this.programCount = programCount;
            this.summaries = Collections.unmodifiableList(new ArrayList<>(summaries));
        }

        public int getProgramCount() {
            return programCount;
        }

        public List<String> getSummaries() {
            return summaries;
        }
    }

    public static final class ZoneStatus {

        private final Set<Integer> availableZones;
        private final int slotCount;
        private final int activeZone;
        private final int remainingRuntime;

        public ZoneStatus(Set<Integer> availableZones, int slotCount, int activeZone, int remainingRuntime) {
            this.availableZones = Collections.unmodifiableSet(new HashSet<>(availableZones));
            this.slotCount = slotCount;
            this.activeZone = activeZone;
            this.remainingRuntime = remainingRuntime;
        }

        public Set<Integer> getAvailableZones() {
            return availableZones;
        }

        public int getSlotCount() {
            return slotCount;
        }

        public int getActiveZone() {
            return activeZone;
        }

        public int getRemainingRuntime() {
            return remainingRuntime;
        }
    }

    public static final class ModelAndVersion {

        private final int modelId;
        private final int protocolMajor;
        private final int protocolMinor;
        private final ModelInfo modelInfo;

        public ModelAndVersion(int modelId, int protocolMajor, int protocolMinor) {
            this.modelId = modelId;
            this.protocolMajor = protocolMajor;
            this.protocolMinor = protocolMinor;
            this.modelInfo = ModelInfoRegistry.lookup(modelId);
        }

        public int getModelId() {
            return modelId;
        }

        public int getProtocolMajor() {
            return protocolMajor;
        }

        public int getProtocolMinor() {
            return protocolMinor;
        }

        public String getModelCode() {
            return modelInfo.getCode();
        }

        public String getModelName() {
            return modelInfo.getName();
        }
    }

    public static final class ControllerFirmwareVersion {

        private final int major;
        private final int minor;
        private final int patch;

        public ControllerFirmwareVersion(int major, int minor, int patch) {
            this.major = major;
            this.minor = minor;
            this.patch = patch;
        }

        public int getMajor() {
            return major;
        }

        public int getMinor() {
            return minor;
        }

        public int getPatch() {
            return patch;
        }

        public String asVersionString() {
            return major + "." + minor + "." + patch;
        }
    }

    public static final class ZipCodeInfo {

        private final @Nullable String code;
        private final @Nullable String country;

        public ZipCodeInfo(@Nullable String code, @Nullable String country) {
            this.code = code;
            this.country = country;
        }

        public @Nullable String getCode() {
            return code;
        }

        public @Nullable String getCountry() {
            return country;
        }
    }

    public static final class WeatherStatus {

        private final @Nullable String stickId;
        private final @Nullable String controllerName;
        private final Map<Integer, String> customStationNames;

        public WeatherStatus(@Nullable String stickId, @Nullable String controllerName,
                Map<Integer, String> customStationNames) {
            this.stickId = stickId;
            this.controllerName = controllerName;
            this.customStationNames = Collections.unmodifiableMap(new LinkedHashMap<>(customStationNames));
        }

        public @Nullable String getStickId() {
            return stickId;
        }

        public @Nullable String getControllerName() {
            return controllerName;
        }

        public Map<Integer, String> getCustomStationNames() {
            return customStationNames;
        }
    }

    public static final class NetworkStatus {

        private final boolean networkUp;
        private final boolean internetUp;

        public NetworkStatus(boolean networkUp, boolean internetUp) {
            this.networkUp = networkUp;
            this.internetUp = internetUp;
        }

        public boolean isNetworkUp() {
            return networkUp;
        }

        public boolean isInternetUp() {
            return internetUp;
        }
    }

    public static final class WifiStatus {

        private final int rssi;
        private final @Nullable String ssid;
        private final @Nullable String macAddress;
        private final @Nullable String firmwareVersion;

        public WifiStatus(int rssi, @Nullable String ssid, @Nullable String macAddress,
                @Nullable String firmwareVersion) {
            this.rssi = rssi;
            this.ssid = ssid;
            this.macAddress = macAddress;
            this.firmwareVersion = firmwareVersion;
        }

        public int getRssi() {
            return rssi;
        }

        public @Nullable String getSsid() {
            return ssid;
        }

        public @Nullable String getMacAddress() {
            return macAddress;
        }

        public @Nullable String getFirmwareVersion() {
            return firmwareVersion;
        }
    }

    public static final class CombinedState {

        private final int delaySetting;
        private final int sensorState;
        private final int irrigationState;
        private final int seasonalAdjust;
        private final int remainingRuntime;
        private final int activeStation;
        private final LocalDateTime controllerTime;

        public CombinedState(int delaySetting, int sensorState, int irrigationState, int seasonalAdjust, int remainingRuntime,
                int activeStation, LocalDateTime controllerTime) {
            this.delaySetting = delaySetting;
            this.sensorState = sensorState;
            this.irrigationState = irrigationState;
            this.seasonalAdjust = seasonalAdjust;
            this.remainingRuntime = remainingRuntime;
            this.activeStation = activeStation;
            this.controllerTime = controllerTime;
        }

        public int getDelaySetting() {
            return delaySetting;
        }

        public int getSensorState() {
            return sensorState;
        }

        public int getIrrigationState() {
            return irrigationState;
        }

        public int getSeasonalAdjust() {
            return seasonalAdjust;
        }

        public int getRemainingRuntime() {
            return remainingRuntime;
        }

        public int getActiveStation() {
            return activeStation;
        }

        public LocalDateTime getControllerTime() {
            return controllerTime;
        }
    }

    private static final class AvailableStationsData {

        private final Set<Integer> activeZones;
        private final int slotCount;

        AvailableStationsData(Set<Integer> activeZones, int slotCount) {
            this.activeZones = activeZones;
            this.slotCount = slotCount;
        }

        Set<Integer> activeZones() {
            return activeZones;
        }

        int slotCount() {
            return slotCount;
        }
    }

    private enum StickCommand {
        MODEL_AND_VERSION("02", 1),
        AVAILABLE_STATIONS("03", 2),
        RETRIEVE_SCHEDULE("20", 3),
        MANUALLY_RUN_PROGRAM("38", 2),
        MANUALLY_RUN_STATION("39", 4),
        STOP_IRRIGATION("40", 1),
        COMBINED_CONTROLLER_STATE("4C", 1),
        CONTROLLER_FIRMWARE_VERSION("0B", 1);

        private final String commandCode;
        private final int length;

        StickCommand(String commandCode, int length) {
            this.commandCode = commandCode;
            this.length = length;
        }

        public String encode(Object... args) {
            StringBuilder builder = new StringBuilder(commandCode);
            if (this == RETRIEVE_SCHEDULE) {
                int value = args.length > 0 ? toInt(args[0]) : 0;
                builder.append(String.format("%04X", Integer.valueOf(value & 0xFFFF)));
                return builder.toString();
            }
            if (this == MANUALLY_RUN_STATION) {
                int zone = args.length > 0 ? toInt(args[0]) : 0;
                int minutes = args.length > 1 ? toInt(args[1]) : 0;
                builder.append(String.format("%04X%02X", Integer.valueOf(zone & 0xFFFF), Integer.valueOf(minutes & 0xFF)));
                return builder.toString();
            }
            for (Object arg : args) {
                builder.append(String.format("%02X", Integer.valueOf(toInt(arg) & 0xFF)));
            }
            return builder.toString();
        }

        public int commandEcho() {
            return Integer.parseInt(commandCode, 16);
        }
    }

    @FunctionalInterface
    private interface SipDecoder<T> {
        T decode(StickCommand command, String data) throws IOException, InterruptedException;
    }

    private static int toInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            return Integer.parseInt((String) value);
        }
        throw new IllegalArgumentException("Unsupported argument type: " + value);
    }

    private static String toHexDump(byte[] data) {
        if (data == null) {
            return "<null>";
        }
        StringBuilder sb = new StringBuilder();
        int offset = 0;
        for (int i = 0; i < data.length; i += 16) {
            sb.append(String.format("%04X: ", Integer.valueOf(offset)));
            int j;
            for (j = 0; j < 16 && i + j < data.length; j++) {
                sb.append(String.format("%02X ", Integer.valueOf(data[i + j] & 0xFF)));
            }
            for (; j < 16; j++) {
                sb.append("   ");
            }
            sb.append("  ");
            for (j = 0; j < 16 && i + j < data.length; j++) {
                int b = data[i + j] & 0xFF;
                char ch = (b >= 32 && b <= 126) ? (char) b : '.';
                sb.append(ch);
            }
            sb.append('\n');
            offset += 16;
        }
        return sb.toString();
    }

}
