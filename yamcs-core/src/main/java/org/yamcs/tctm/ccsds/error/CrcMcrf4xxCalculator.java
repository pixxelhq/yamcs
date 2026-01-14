package org.yamcs.tctm.ccsds.error;

import java.nio.ByteBuffer;

import org.yamcs.YConfiguration;
import org.yamcs.tctm.ErrorDetectionWordCalculator;

public class CrcMcrf4xxCalculator implements ErrorDetectionWordCalculator {
    final int initialValue;
    final int polynomial = 0x8408;

    private final Crc16Mcrf4xxCalculator crc = new Crc16Mcrf4xxCalculator(polynomial);

    public CrcMcrf4xxCalculator() {
        initialValue = 0xFFFF;
    }

    public CrcMcrf4xxCalculator(YConfiguration c) {
        initialValue = c.getInt("initialValue", 0xFFFF);
    }

    @Override
    public int compute(byte[] data, int offset, int length) {
        return crc.compute(data, offset, length, initialValue);
    }

    public int compute(ByteBuffer data, int offset, int length) {
        return crc.compute(data, offset, length, initialValue);
    }

    @Override
    public int sizeInBits() {
        return 16;
    }
}
