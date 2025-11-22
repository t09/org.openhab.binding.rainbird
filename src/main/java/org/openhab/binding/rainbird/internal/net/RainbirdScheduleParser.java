package org.openhab.binding.rainbird.internal.net;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Parser for schedule information returned by the Rain Bird controller.
 */
@NonNullByDefault
final class RainbirdScheduleParser {

    private final int programCount;
    private final Set<Integer> activeZones;
    private final Map<Integer, Program> programs = new HashMap<>();

    RainbirdScheduleParser(int programCount, Set<Integer> activeZones) {
        this.programCount = Math.max(0, programCount);
        this.activeZones = activeZones;
    }

    public void accept(String data) {
        if (data.length() < 2) {
            return;
        }
        String code = data.substring(0, 2);
        if ("A0".equals(code)) {
            handleRetrieveSchedule(data);
        }
        // Water budget (B0) and acknowledgements (01) are ignored for summaries.
    }

    public List<String> buildSummaries() {
        List<String> summaries = new ArrayList<>();
        for (int programIndex = 0; programIndex < programCount; programIndex++) {
            Program program = programs.computeIfAbsent(programIndex, Program::new);
            summaries.add(program.summary(activeZones));
        }
        return Collections.unmodifiableList(summaries);
    }

    private void handleRetrieveSchedule(String data) {
        if (data.length() < 6) {
            return;
        }
        int subcommand = Integer.parseInt(data.substring(4, 6), 16);
        if (subcommand == 0) {
            // Controller info (rain delay etc) is not exposed as a summary yet.
            return;
        }
        if ((subcommand & 16) == 16) {
            // Program metadata (frequency, period) is not exposed currently.
            return;
        }
        if ((subcommand & 96) == 96) {
            handleProgramStart(subcommand & ~96, data.substring(6));
            return;
        }
        if ((subcommand & 128) == 128) {
            handleZoneDurations(subcommand & ~128, data.substring(6));
        }
    }

    private void handleProgramStart(int programIndex, String rest) {
        Program program = programs.computeIfAbsent(programIndex, Program::new);
        for (int i = 0; i + 4 <= rest.length(); i += 4) {
            int value = Integer.parseInt(rest.substring(i, i + 4), 16);
            if (value >= 65535) {
                continue;
            }
            int hour = value / 60;
            int minute = value % 60;
            program.startTimes.add(String.format("%02d:%02d", hour, minute));
        }
    }

    private void handleZoneDurations(int zonePage, String rest) {
        int zoneBase = zonePage * 2;
        List<Integer> durations = new ArrayList<>();
        for (int i = 0; i + 4 <= rest.length(); i += 4) {
            durations.add(Integer.parseInt(rest.substring(i, i + 4), 16));
        }
        int entriesPerZone = durations.size() / 2;
        if (entriesPerZone == 0) {
            return;
        }
        for (int zoneOffset = 0; zoneOffset < 2; zoneOffset++) {
            int zoneNumber = zoneBase + zoneOffset + 1;
            if (!activeZones.isEmpty() && !activeZones.contains(zoneNumber)) {
                continue;
            }
        // Ensure map entries exist for all programs even if we later overwrite them.
        for (int programIndex = 0; programIndex < programCount; programIndex++) {
            programs.computeIfAbsent(programIndex, Program::new);
        }
            for (int programIndex = 0; programIndex < entriesPerZone && programIndex < programCount; programIndex++) {
                int duration = durations.get(zoneOffset * entriesPerZone + programIndex);
                if (duration <= 0) {
                    continue;
                }
                Program current = programs.computeIfAbsent(programIndex, Program::new);
                current.zoneDurations.put(zoneNumber, duration);
            }
        }
    }

    private static final class Program {

        private final int index;
        private final List<String> startTimes = new ArrayList<>();
        private final Map<Integer, Integer> zoneDurations = new TreeMap<>();
        Program(int index) {
            this.index = index;
        }

        String summary(Set<Integer> activeZones) {
            String programName = buildProgramName(index);
            String starts = startTimes.isEmpty() ? "No starts" : String.join(", ", startTimes);
            if (!startTimes.isEmpty()) {
                starts = "Starts " + starts;
            }
            Map<Integer, Integer> relevantDurations = zoneDurations;
            if (!activeZones.isEmpty()) {
                Map<Integer, Integer> filtered = new LinkedHashMap<>();
                for (Map.Entry<Integer, Integer> entry : zoneDurations.entrySet()) {
                    if (activeZones.contains(entry.getKey())) {
                        filtered.put(entry.getKey(), entry.getValue());
                    }
                }
                relevantDurations = filtered;
            }
            String zones;
            if (relevantDurations.isEmpty()) {
                zones = "No zones";
            } else {
                List<String> parts = new ArrayList<>();
                for (Map.Entry<Integer, Integer> entry : relevantDurations.entrySet()) {
                    parts.add(entry.getKey() + "=" + entry.getValue() + "m");
                }
                zones = "Zones " + String.join(", ", parts);
            }
            if (!startTimes.isEmpty() && starts.startsWith("Starts")) {
                return programName + ": " + starts + "; " + zones;
            }
            if (startTimes.isEmpty()) {
                starts = "No starts";
            }
            return programName + ": " + starts + "; " + zones;
        }

        private static String buildProgramName(int index) {
            if (index >= 0 && index < 26) {
                return "Program " + (char) ('A' + index);
            }
            return "Program " + (index + 1);
        }
    }
}
