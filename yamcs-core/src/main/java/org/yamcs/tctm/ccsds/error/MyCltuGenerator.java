package org.yamcs.tctm.ccsds.error;

public class MyCltuGenerator {
    private final int maxOffset;
    private int offset;
    private final byte[] rseq;

    public MyCltuGenerator(boolean is64QAM) {
        offset = 0;

        // Determine maxOffset based on modulation type
        maxOffset = is64QAM ? (60 * 128) : (88 * 128);
        rseq = new byte[maxOffset];

        // Initialize the random sequence
        initRand();
    }

    // Initialize the pseudo-random sequence (rseq)
    private void initRand() {
        byte c2 = 0x7F, c1 = 0x7F, c0 = 0x7F;
        byte c2New, c1New, c0New;

        for (int n = 0; n < maxOffset; n++) {
            rseq[n] = c2;
            c2New = c1;
            c1New = (byte) (c0 ^ c2);
            c0New = c2;

            for (int i = 0; i < 3; i++) {
                c0New <<= 1;
                if ((c0New & 0x80) != 0) {
                    c0New = (byte) ((c0New & 0x7F) ^ 0x09);
                }
            }

            c2 = c2New;
            c1 = c1New;
            c0 = c0New;
        }
    }

    // Randomize the input data
    public byte[] randomize(byte[] input) {
        byte[] output = new byte[input.length];

        for (int i = 0; i < input.length; i++) {
            output[i] = (byte) (input[i] ^ rseq[offset++]);
            if (offset == maxOffset) {
                offset = 0; // Reset offset if it reaches maxOffset
            }
        }

        return output;
    }
}