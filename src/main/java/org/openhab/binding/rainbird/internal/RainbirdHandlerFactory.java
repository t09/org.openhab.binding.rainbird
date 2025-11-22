package org.openhab.binding.rainbird.internal;

import static org.openhab.binding.rainbird.internal.RainbirdBindingConstants.*;

import java.util.Set;

import org.openhab.binding.rainbird.internal.handler.RainbirdBridgeHandler;
import org.openhab.binding.rainbird.internal.handler.RainbirdZoneHandler;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.BaseThingHandlerFactory;
import org.openhab.core.thing.binding.ThingHandler;
import org.openhab.core.thing.binding.ThingHandlerFactory;
import org.osgi.service.component.annotations.Component;

/**
 * Erstellt die passenden Handler für Bridge/Things.
 */
@Component(service = ThingHandlerFactory.class)
public class RainbirdHandlerFactory extends BaseThingHandlerFactory {

    private static final Set<ThingTypeUID> SUPPORTED_THING_TYPES = Set.of(BRIDGE_TYPE_UID, ZONE_TYPE_UID);

    @Override
    public boolean supportsThingType(ThingTypeUID thingTypeUID) {
        return SUPPORTED_THING_TYPES.contains(thingTypeUID);
    }

    @Override
    protected ThingHandler createHandler(Thing thing) {
        ThingTypeUID type = thing.getThingTypeUID();

        if (BRIDGE_TYPE_UID.equals(type)) {
            return new RainbirdBridgeHandler((Bridge) thing);
        } else if (ZONE_TYPE_UID.equals(type)) {
            return new RainbirdZoneHandler(thing);
        }

        return null;
    }
}
