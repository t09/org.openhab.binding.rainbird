package org.openhab.binding.rainbird.internal.util;

import java.util.Map;
import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Provides metadata about known Rain Bird controller models.
 */
@NonNullByDefault
public final class ModelInfoRegistry {

    private static final Map<Integer, ModelInfo> MODELS = Map.ofEntries(
            Map.entry(Integer.valueOf(0x0003), new ModelInfo("ESP_RZXe", "ESP-RZXe")),
            Map.entry(Integer.valueOf(0x0005), new ModelInfo("ESP_TM2", "ESP-TM2")),
            Map.entry(Integer.valueOf(0x0006), new ModelInfo("ST8X_WF", "ST8x-WiFi")),
            Map.entry(Integer.valueOf(0x0007), new ModelInfo("ESP_ME", "ESP-Me")),
            Map.entry(Integer.valueOf(0x0008), new ModelInfo("ST8X_WF2", "ST8x-WiFi2")),
            Map.entry(Integer.valueOf(0x0009), new ModelInfo("ESP_ME3", "ESP-ME3")),
            Map.entry(Integer.valueOf(0x000A), new ModelInfo("ESP_TM2v2", "ESP-TM2")),
            Map.entry(Integer.valueOf(0x0010), new ModelInfo("MOCK_ESP_ME2", "ESP=Me2")),
            Map.entry(Integer.valueOf(0x0099), new ModelInfo("TBOS_BT", "TBOS-BT")),
            Map.entry(Integer.valueOf(0x0100), new ModelInfo("TBOS_BT", "TBOS-BT")),
            Map.entry(Integer.valueOf(0x0103), new ModelInfo("ESP_RZXe2", "ESP-RZXe2")),
            Map.entry(Integer.valueOf(0x0107), new ModelInfo("ESP_MEv2", "ESP-Me")),
            Map.entry(Integer.valueOf(0x010A), new ModelInfo("ESP_TM2v3", "ESP-TM2")),
            Map.entry(Integer.valueOf(0x0812), new ModelInfo("RC2", "RC2")),
            Map.entry(Integer.valueOf(0x0813), new ModelInfo("ARC8", "ARC8")));

    private ModelInfoRegistry() {
    }

    /**
     * Look up model metadata by numeric identifier.
     */
    public static ModelInfo lookup(int modelId) {
        return MODELS.getOrDefault(Integer.valueOf(modelId), ModelInfo.UNKNOWN);
    }

    /**
     * Immutable container for the controller metadata.
     */
    public static final class ModelInfo {
        public static final ModelInfo UNKNOWN = new ModelInfo("UNKNOWN", "Unknown");

        private final String code;
        private final String name;

        ModelInfo(String code, String name) {
            this.code = Objects.requireNonNull(code);
            this.name = Objects.requireNonNull(name);
        }

        public String getCode() {
            return code;
        }

        public String getName() {
            return name;
        }
    }
}
