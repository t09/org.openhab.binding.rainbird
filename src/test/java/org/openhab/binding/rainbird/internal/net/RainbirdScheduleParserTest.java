package org.openhab.binding.rainbird.internal.net;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.junit.jupiter.api.Test;

/**
 * Tests the schedule parser against captured controller responses.
 */
/**
 * Verifiziert das Parsing von Programmen und Zeitplänen.
 */
@NonNullByDefault
class RainbirdScheduleParserTest {

    @Test
    void parseScheduleProducesSummaries() {
        List<String> responses = List.of(
                "A0000000000400",
                "A000106A0601006401",
                "A000117F0300002D00",
                "A00012000300006400",
                "A0006000F0FFFFFFFFFFFF",
                "A000610168FFFFFFFFFFFF",
                "A00062FFFFFFFFFFFFFFFF",
                "A00080001900010000001400020000",
                "A00081000700030000001400040000",
                "A00082000A00060000000000000000",
                "B0000064",
                "B0010050",
                "B0020050",
                "0131");

        RainbirdScheduleParser parser = new RainbirdScheduleParser(3, Objects.requireNonNull(Set.of(1, 2, 3, 4, 5, 6)));
        for (String response : responses) {
            parser.accept(response);
        }
        List<String> summaries = parser.buildSummaries();
        assertEquals(3, summaries.size());
        assertEquals("Program A: Starts 04:00; Zones 1=25m, 2=20m, 3=7m, 4=20m, 5=10m",
                Objects.requireNonNull(summaries.get(0)));
        assertEquals("Program B: Starts 06:00; Zones 1=1m, 2=2m, 3=3m, 4=4m, 5=6m",
                Objects.requireNonNull(summaries.get(1)));
        assertEquals("Program C: No starts; No zones", Objects.requireNonNull(summaries.get(2)));
    }
}
