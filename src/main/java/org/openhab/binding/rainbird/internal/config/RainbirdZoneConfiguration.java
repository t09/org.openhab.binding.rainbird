package org.openhab.binding.rainbird.internal.config;

import org.eclipse.jdt.annotation.NonNullByDefault;

/**
 * Configuration for individual Rain Bird irrigation zones.
 */
@NonNullByDefault
public class RainbirdZoneConfiguration {

    /**
     * Numerical identifier used by the controller to address the zone.
     */
    public int zoneNumber = 1;

    /**
     * Default manual watering duration in minutes.
     */
    public int defaultDuration = 5;
}
