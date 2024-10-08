package org.yamcs.tctm;

/**
 * 
 * Decodes raw frames performing derandomization and error correction.
 * <p>
 * We assume the all frames are of pre-configured fixed length.
 */
public interface RawFrameEnDec {
    /**
     * Decodes frame in the buffer at offset. The decoded frame is stored in the same buffer.
     * <p>
     * Returns the length of the decoded and corrected frame or -1 if the frame could not be decoded
     */
    int decodeFrame(byte[] data, int offset, int length);

    /**
     * Returns the length of the encoded frame or -1 if it can be variable
     * 
     * @return
     */
    int encodedFrameLength();

    /**
     * Returns the length of the decoded frame or -1 if it can be variable
     * 
     * @return
     */
    int decodedFrameLength();

    /**
     * Encodes frame in the buffer at offset. The Encoded frame is stored in the same buffer.
     * <p>
     * Returns the length of the encoded frame
     */
    int encodeFrame(byte[] data, int length, int vf);

}
