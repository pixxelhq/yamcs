package org.yamcs.tctm.ccsds.error;

import java.nio.ByteBuffer;

public class Crc16Mcrf4xxCalculator {
    private final int polynomial;
    private final short[] table = new short[256];

    public Crc16Mcrf4xxCalculator(int polynomial) {
        this.polynomial = polynomial;
        init();
    }

    private void init() {
        for (int i = 0; i < 256; i++) {
            int crc = i;
            for (int j = 0; j < 8; j++) {
                if ((crc & 0x0001) != 0) {
                    crc = (crc >>> 1) ^ polynomial;
                } else {
                    crc >>>= 1;
                }
            }
            table[i] = (short) crc;
        }
    }

    public int compute(byte[] data, int offset, int length, int initialValue) {
        int crc = initialValue;

        for (int i = offset; i < offset + length; i++) {
            crc = (crc >>> 8) ^ table[(crc ^ data[i]) & 0xFF];
        }

        return crc & 0xFFFF;
    }

    public int compute(ByteBuffer bb, int offset, int length, int initialValue) {
        int crc = initialValue;

        for (int i = offset; i < offset + length; i++) {
            crc = (crc >>> 8) ^ table[(crc ^ bb.get(i)) & 0xFF];
        }

        return crc & 0xFFFF;
    }
}
