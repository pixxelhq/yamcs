package org.yamcs.tctm.ccsds.srs4;

import java.util.HashMap;
import java.util.Map;

import org.yamcs.tctm.TcTmException;
import org.yamcs.tctm.csp.CspPacket;

import org.yamcs.tctm.ccsds.srs4.Srs4Config.CspEndpoint;
import org.yamcs.tctm.ccsds.srs4.Srs4Config.CspSettings;

final class Srs4CspHeaderCodec {
    static final int HEADER_LENGTH = 4;

    record DecodedCspFrame(byte[] data, int offset, int length, int virtualChannelId) {
    }

    private final CspSettings settings;
    private final Map<Integer, Integer> sourceRoutes = new HashMap<>();

    Srs4CspHeaderCodec(CspSettings settings) {
        this.settings = settings;
    }

    void addSourceRoute(CspEndpoint endpoint, int vcId) {
        Integer previous = sourceRoutes.putIfAbsent(endpoint.address(), vcId);
        if (previous != null && previous != vcId) {
            throw new IllegalArgumentException("Duplicate SRS4 CSP source address for vcId " + previous
                    + " and vcId " + vcId);
        }
    }

    byte[] encode(CspEndpoint destination, byte[] payload) {
        byte[] result = new byte[HEADER_LENGTH + payload.length];
        CspEndpoint source = settings.fixedEndpoint();
        new CspPacket(result).setHeader(
            (byte) 0,
            (byte) source.address(), (byte) destination.address(),
            (byte) destination.port(), (byte) source.port(),
            false, false, false, false
        );
        System.arraycopy(payload, 0, result, HEADER_LENGTH, payload.length);
        return result;
    }

    DecodedCspFrame decode(byte[] data, int offset, int length) throws TcTmException {
        if (length < HEADER_LENGTH) {
            throw new TcTmException("SRS4 CSP frame is shorter than 4 bytes");
        }
        int word = ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
        int sourceAddress = (word >>> 25) & 0x1F;
        int destinationAddress = (word >>> 20) & 0x1F;
        int destinationPort = (word >>> 14) & 0x3F;

        if ((word & 0xF0) != 0) {
            throw new TcTmException("SRS4 CSP reserved bits are not zero");
        }
        CspEndpoint destination = settings.fixedEndpoint();
        if (destinationAddress != destination.address() || destinationPort != destination.port()) {
            throw new TcTmException("Unexpected SRS4 CSP destination endpoint");
        }
        Integer vcId = sourceRoutes.get(sourceAddress);
        if (vcId == null) {
            throw new TcTmException("Unknown SRS4 CSP source address " + sourceAddress);
        }
        return new DecodedCspFrame(data, offset + HEADER_LENGTH, length - HEADER_LENGTH, vcId);
    }
}
