package org.yamcs.tctm.ccsds;

import java.util.Collection;

import org.yamcs.tctm.TcTmException;

/** Removes mission-specific outer layers before CCSDS transfer-frame decoding. */
public interface TmFrameDecapsulator {

    record DecapsulatedFrame(byte[] data, int offset, int length, Integer expectedVirtualChannelId) {
    }

    /**
     * Removes mission-specific outer layers, optionally validating against a fixed expected inner frame length.
     *
     * @param expectedInnerFrameLength
     *            the expected inner frame length, or {@code -1} when it is not fixed
     */
    DecapsulatedFrame decapsulate(byte[] data, int offset, int length, int expectedInnerFrameLength) throws TcTmException;

    /** Maximum number of bytes which may surround a inner transfer frame. */
    int maxFrameOverhead();

    /** Validates the provider configuration against the parent data link. */
    default void validate(int maximumFrameLength, Collection<Integer> virtualChannelIds) {
    }
}
