package org.yamcs.tctm.ccsds.error;

import java.util.Arrays;

import org.yamcs.tctm.ErrorDetectionWordCalculator;

public class CrcCciit8Calculator  implements ErrorDetectionWordCalculator {
    final int initialValue;
    final int polynomial = 0xD5;

    public CrcCciit8Calculator() {
        initialValue = 0x00;
    }

    @Override
    public int compute(byte[] data, int offset, int length) {
        byte[] bytec = Arrays.copyOfRange(data, offset, length + offset);
        int crc = initialValue;

        for (byte b : bytec) {
            crc ^= b & 0xFF; // XOR byte into current CRC

            for (int i = 0; i < 8; i++) {
                if ((crc & 0x80) != 0) {
                    crc = (crc << 1) ^ polynomial; // Shift left and XOR with polynomial
                } else {
                    crc <<= 1; // Just shift left
                }
            }
        }

        return (crc & 0xFF); // Return the lower 8 bits
    }

    @Override
    public int sizeInBits() {
        return 8;
    }
    
}
