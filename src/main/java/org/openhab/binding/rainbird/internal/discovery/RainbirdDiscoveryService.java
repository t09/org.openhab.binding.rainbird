package org.openhab.binding.rainbird.internal.discovery;

import static org.openhab.binding.rainbird.internal.RainbirdBindingConstants.BINDING_ID;
import static org.openhab.binding.rainbird.internal.RainbirdBindingConstants.BRIDGE_TYPE_UID;
import static org.openhab.binding.rainbird.internal.RainbirdBindingConstants.CONFIG_HOST;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.core.config.discovery.AbstractDiscoveryService;
import org.openhab.core.config.discovery.DiscoveryResult;
import org.openhab.core.config.discovery.DiscoveryResultBuilder;
import org.openhab.core.config.discovery.ScanListener;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Very small discovery service that tries to resolve the mDNS name that the Rain Bird Wi-Fi module
 * advertises on the local network.
 */
@Component(service = org.openhab.core.config.discovery.DiscoveryService.class, immediate = true, configurationPid = BINDING_ID)
@NonNullByDefault
public class RainbirdDiscoveryService extends AbstractDiscoveryService {

    private static final ThingTypeUID BRIDGE_UID = BRIDGE_TYPE_UID;
    private final Logger logger = LoggerFactory.getLogger(RainbirdDiscoveryService.class);

    @Activate
    public RainbirdDiscoveryService(@Nullable Map<String, Object> configProperties) {
        super(Set.of(BRIDGE_UID), 10, false);
        activate(configProperties);
    }

    @Override
    protected void startScan() {
        try {
            InetAddress address = InetAddress.getByName("RainBird.localdomain");
            if (address != null) {
                Map<String, Object> properties = new HashMap<>();
                properties.put(CONFIG_HOST, address.getHostAddress());
                ThingUID thingUID = new ThingUID(BRIDGE_UID, "rainbird-local");
                DiscoveryResult result = DiscoveryResultBuilder.create(thingUID).withProperties(properties)
                        .withRepresentationProperty(CONFIG_HOST).withLabel("Rain Bird Controller (Local)").build();
                thingDiscovered(result);
            }
        } catch (UnknownHostException e) {
            logger.debug("Could not resolve RainBird.localdomain", e);
            notifyScanError(e);
        } finally {
            stopScan();
        }
    }

    @Modified
    protected void modified(@Nullable Map<String, Object> configProperties) {
        super.modified(configProperties);
    }

    @Deactivate
    protected void deactivate() {
        super.deactivate();
    }

    private void notifyScanError(Exception e) {
        ScanListener listener = this.scanListener;
        if (listener != null) {
            listener.onErrorOccurred(e);
        }
    }
}
