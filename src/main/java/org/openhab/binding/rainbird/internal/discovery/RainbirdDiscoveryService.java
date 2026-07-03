package org.openhab.binding.rainbird.internal.discovery;

import static org.openhab.binding.rainbird.internal.RainbirdBindingConstants.BINDING_ID;
import static org.openhab.binding.rainbird.internal.RainbirdBindingConstants.BRIDGE_TYPE_UID;
import static org.openhab.binding.rainbird.internal.RainbirdBindingConstants.CONFIG_HOST;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.openhab.binding.rainbird.internal.RainbirdBindingConstants.CONFIG_PORT;

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
 * Very small discovery service that tries to resolve the mDNS name that the
 * Rain Bird Wi-Fi module
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
                String ip = address.getHostAddress();
                String hostConfig = ip;
                int portConfig = 80;
                
                int[] httpPorts = {80, 81, 8080};
                int[] httpsPorts = {443, 8443};
                boolean found = false;
                
                // Check common HTTP ports
                for (int p : httpPorts) {
                    if (isPortOpen(ip, p)) {
                        portConfig = p;
                        hostConfig = ip;
                        found = true;
                        break;
                    }
                }
                
                // Check common HTTPS ports if no HTTP port is open
                if (!found) {
                    for (int p : httpsPorts) {
                        if (isPortOpen(ip, p)) {
                            portConfig = p;
                            hostConfig = ip;
                            break;
                        }
                    }
                }
                
                Map<String, Object> properties = new HashMap<>();
                properties.put(CONFIG_HOST, hostConfig);
                properties.put(CONFIG_PORT, java.math.BigDecimal.valueOf(portConfig));
                
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

    private boolean isPortOpen(String ip, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), 1000);
            return true;
        } catch (Exception e) {
            return false;
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
