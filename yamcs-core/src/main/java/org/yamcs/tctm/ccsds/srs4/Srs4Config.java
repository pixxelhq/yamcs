package org.yamcs.tctm.ccsds.srs4;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.yamcs.ConfigurationException;
import org.yamcs.YConfiguration;

final class Srs4Config {
    record CspEndpoint(int address, int port) {
    }

    record Ipv4Endpoint(int address, int port) {
    }

    record Route(CspEndpoint csp, Ipv4Endpoint ipv4Udp) {
    }

    record TmRoute(int vcId, List<CspEndpoint> csp, List<Ipv4Endpoint> ipv4Udp) {
    }

    record CspSettings(boolean enabled, CspEndpoint fixedEndpoint, int priority, boolean hmac, boolean xtea,
            boolean rdp, boolean crc) {
    }

    record Ipv4UdpSettings(boolean enabled, Ipv4Endpoint fixedEndpoint, int ttl, boolean calculateUdpChecksum) {
    }

    final int radioSpacecraftId;
    final CspSettings csp;
    final Ipv4UdpSettings ipv4Udp;
    final Map<Integer, Route> routes;
    final List<TmRoute> tmRoutes;
    final boolean dualFlow;
    final Srs4Flow fixedFlow;
    final Srs4Flow controlFrameFlow;

    private Srs4Config(int radioSpacecraftId, CspSettings csp, Ipv4UdpSettings ipv4Udp,
            Map<Integer, Route> routes, List<TmRoute> tmRoutes, Srs4Flow controlFrameFlow) {
        this.radioSpacecraftId = radioSpacecraftId;
        this.csp = csp;
        this.ipv4Udp = ipv4Udp;
        this.routes = routes;
        this.tmRoutes = tmRoutes;
        dualFlow = csp.enabled() && ipv4Udp.enabled();
        fixedFlow = dualFlow ? null : csp.enabled() ? Srs4Flow.CAN : Srs4Flow.ETHERNET;
        this.controlFrameFlow = controlFrameFlow;
    }

    static Srs4Config forTc(YConfiguration args) {
        return parse(args, true);
    }

    static Srs4Config forTm(YConfiguration args) {
        return parse(args, false);
    }

    private static Srs4Config parse(YConfiguration args, boolean tc) {
        if (!args.containsKey("srs4")) {
            throw new ConfigurationException("SRS4 frame provider requires an 'srs4' configuration block");
        }
        YConfiguration config = args.getConfig("srs4");
        boolean radioEnabled = enabled(config, "radio");
        boolean cspEnabled = enabled(config, "csp");
        boolean ipEnabled = enabled(config, "ipv4Udp");

        if (!cspEnabled && !ipEnabled) {
            throw new ConfigurationException("SRS4 requires at least one of the CSP or IPv4/UDP layers");
        }
        if (!radioEnabled) {
            throw new ConfigurationException("The SRS4 radio layer is required when a bus-header layer is enabled");
        }

        YConfiguration radio = config.getConfig("radio");
        int radioId = radio.getInt("spacecraftId");
        checkRange("radio spacecraftId", radioId, 0, 0xFFFF);

        CspSettings csp = cspEnabled ? parseCsp(config.getConfig("csp"), tc)
                : new CspSettings(false, null, 0, false, false, false, false);
        Ipv4UdpSettings ip = ipEnabled ? parseIpv4Udp(config.getConfig("ipv4Udp"), tc)
                : new Ipv4UdpSettings(false, null, 64, false);

        Map<Integer, Route> routes = new HashMap<>();
        List<TmRoute> tmRoutes = new ArrayList<>();
        for (YConfiguration routeConfig : config.getConfigList("virtualChannels")) {
            int vcId = routeConfig.getInt("vcId");
            if (tc) {
                if (routes.containsKey(vcId)) {
                    throw new ConfigurationException("Duplicate SRS4 route for vcId " + vcId);
                }
                CspEndpoint cspEndpoint = null;
                if (cspEnabled) {
                    YConfiguration endpoint = routeConfig.getConfig("csp");
                    cspEndpoint = parseCspEndpoint(endpoint, "destination");
                }
                Ipv4Endpoint ipEndpoint = null;
                if (ipEnabled) {
                    YConfiguration endpoint = routeConfig.getConfig("ipv4Udp");
                    ipEndpoint = parseIpv4Endpoint(endpoint, "destination");
                }
                routes.put(vcId, new Route(cspEndpoint, ipEndpoint));
            } else {
                List<CspEndpoint> cspEndpoints = cspEnabled ? parseCspEndpoints(routeConfig) : List.of();
                List<Ipv4Endpoint> ipEndpoints = ipEnabled ? parseIpv4Endpoints(routeConfig) : List.of();
                tmRoutes.add(new TmRoute(vcId, cspEndpoints, ipEndpoints));
            }
        }

        Srs4Flow controlFlow = config.getEnum("controlFrameFlow", Srs4Flow.class, Srs4Flow.ETHERNET);
        if (!cspEnabled && controlFlow == Srs4Flow.CAN) {
            throw new ConfigurationException("controlFrameFlow CAN requires the SRS4 CSP layer");
        }
        if (!ipEnabled && controlFlow == Srs4Flow.ETHERNET) {
            controlFlow = Srs4Flow.CAN;
        }
        return new Srs4Config(radioId, csp, ip, routes, tmRoutes, controlFlow);
    }

    private static List<CspEndpoint> parseCspEndpoints(YConfiguration routeConfig) {
        if (!routeConfig.containsKey("csp")) {
            throw new ConfigurationException("SRS4 TM route is missing CSP source endpoints");
        }
        List<CspEndpoint> endpoints = new ArrayList<>();
        for (YConfiguration endpoint : routeConfig.getConfigList("csp")) {
            endpoints.add(parseCspEndpoint(endpoint, "source"));
        }
        if (endpoints.isEmpty()) {
            throw new ConfigurationException("SRS4 TM route must contain at least one CSP source endpoint");
        }
        return List.copyOf(endpoints);
    }

    private static List<Ipv4Endpoint> parseIpv4Endpoints(YConfiguration routeConfig) {
        if (!routeConfig.containsKey("ipv4Udp")) {
            throw new ConfigurationException("SRS4 TM route is missing IPv4/UDP source endpoints");
        }
        List<Ipv4Endpoint> endpoints = new ArrayList<>();
        for (YConfiguration endpoint : routeConfig.getConfigList("ipv4Udp")) {
            endpoints.add(parseIpv4Endpoint(endpoint, "source"));
        }
        if (endpoints.isEmpty()) {
            throw new ConfigurationException("SRS4 TM route must contain at least one IPv4/UDP source endpoint");
        }
        return List.copyOf(endpoints);
    }

    private static boolean enabled(YConfiguration config, String key) {
        return config.containsKey(key) && config.getConfig(key).getBoolean("enabled", true);
    }

    private static CspSettings parseCsp(YConfiguration config, boolean tc) {
        CspEndpoint fixed = parseCspEndpoint(config, tc ? "source" : "destination");
        int priority = config.getInt("priority", 0);
        checkRange("CSP priority", priority, 0, 3);
        return new CspSettings(true, fixed, priority, config.getBoolean("hmac", false),
                config.getBoolean("xtea", false), config.getBoolean("rdp", false),
                config.getBoolean("crc", false));
    }

    private static Ipv4UdpSettings parseIpv4Udp(YConfiguration config, boolean tc) {
        Ipv4Endpoint fixed = parseIpv4Endpoint(config, tc ? "source" : "destination");
        int ttl = config.getInt("ttl", 64);
        checkRange("IPv4 TTL", ttl, 1, 255);
        return new Ipv4UdpSettings(true, fixed, ttl, config.getBoolean("calculateUdpChecksum", false));
    }

    private static CspEndpoint parseCspEndpoint(YConfiguration config, String prefix) {
        int address = config.getInt(prefix + "Address");
        int port = config.getInt(prefix + "Port");
        checkRange("CSP " + prefix + "Address", address, 0, 31);
        checkRange("CSP " + prefix + "Port", port, 0, 63);
        return new CspEndpoint(address, port);
    }

    private static Ipv4Endpoint parseIpv4Endpoint(YConfiguration config, String prefix) {
        int address = parseIpv4(config.getString(prefix + "Address"));
        int port = config.getInt(prefix + "Port");
        checkRange("UDP " + prefix + "Port", port, 0, 0xFFFF);
        return new Ipv4Endpoint(address, port);
    }

    static int parseIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            throw new ConfigurationException("Invalid IPv4 address '" + value + "'");
        }
        int result = 0;
        for (String part : parts) {
            int octet;
            try {
                octet = Integer.parseInt(part);
            } catch (NumberFormatException e) {
                throw new ConfigurationException("Invalid IPv4 address '" + value + "'");
            }
            checkRange("IPv4 address octet", octet, 0, 255);
            result = (result << 8) | octet;
        }
        return result;
    }

    private static void checkRange(String name, int value, int minimum, int maximum) {
        if (value < minimum || value > maximum) {
            throw new ConfigurationException(name + " must be between " + minimum + " and " + maximum);
        }
    }
}
