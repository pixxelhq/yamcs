package org.yamcs.utils;

/**
 * Some Mil1750A encoding/decoding functions.
 * 
 * http://www.mssl.ucl.ac.uk/swift/docs/mil-std-1750a.pdf
 * 
 * @author nm
 *
 */
public class MilStd1750A {
    public static long MAX_FLOAT_VALUE = 0x7FFFFF_7F_FFFFL;
    public static long MIN_FLOAT_VALUE = 0xFFFFFF_7F_FFFFL;
    public static int MAX_FLOAT32_VALUE = 0x7FFFFF_7F;
    public static int MIN_FLOAT32_VALUE = 0xFFFFFF_7F;

    /**
     * Encode double value to 48 bits 1750A floating point number.
     * If the number is too large or too small, the {@link #MAX_FLOAT_VALUE} respectively {@link #MIN_FLOAT_VALUE} are
     * returned
     * 
     * This performs some bit operations to transform from IEE 754 binary representation to STD 1750 binary
     * representation.
     * No Math.pow or other expensive operations are used.
     * 
     * @param value
     *            - double number
     * @return encoded 48 bits number (the first 16 bits of the long are 0)
     */
    public static long encode48(double value) {
        long x = Double.doubleToRawLongBits(value);
        if (x == 0) {
            return 0;
        }

        // IEEE 754 numbers have an implicit 1 in front of the mantissa (that's why we do the "| 0x10...")
        // 1750A don't so automatically we increase the exponent with 1
        // (we should also do m = m >> 1 but is done at the end (m>>14 instead of m>>13)
        // you know of course that the IEEE 754 exponents are biased with 1023 and 1023-1=1022 :)
        int e = (int) (((x >> 52) & 0x7FF) - 1022);
        long m = (x & 0xF_FFFF_FFFF_FFFFL) | 0x10_0000_0000_0000L;

        if (x < 0) {
            // this is a negative number, however in IEEE 754 the sign is at the beginning of the number and the
            // mantissa is positive
            // in the 1750A we have to encode the sign in the mantissa
            // the trick is that we know there is a 1 in front of the mantissa (we added it above) - therefore when we
            // change sign (m=-m) the first digit will become 0 - that is true in all cases with one exception
            if (m == 0x10_0000_0000_0000L) {
                // exception: when we change sign this mantissa stays the same
                // that means there will be two binary 1 (the sign plus the first bit of the mantissa) at the beginning
                // of the 750A number which is not allowed.
                // therefore we shift it to the left and decrease the exponent
                m = m << 1;
                e--;
            }
            m = -m;
        }

        if (e > 127) {
            return x > 0 ? MAX_FLOAT_VALUE : MIN_FLOAT_VALUE;
        } else if (e < -128) {
            return 0;
        }
        m = m >> 14;
        return ((m << 8) & 0xFFFFFF_00_0000L) | ((e << 16) & 0xFF_0000) | (m & 0xFFFF);
    }

    /**
     * Decodes a MIL-STD 1750A 48 bit number into a double.
     * 
     * 
     * If the MIL-STD 1750A number is not normalised (first two bits of opposite value), it returns {@link Double#NaN}
     * 
     * @param milstd
     *            - number to be decoded. Only the last 48 bits are considered, the first 16 are ignored.
     * @return - the decoded value
     */
    public static double decode48(long milstd) {

        long m = ((milstd >> 8) & 0xFFFFFF_0000L) | (milstd & 0xFFFF);
        if (m == 0) {
            return 0;
        }
        if(((m>>39) ^ (m>>38)) == 0) { //not a valid number, first two bits should be of opposite value
            return Double.NaN;
        }
        
        // we convert the exponent to byte such that it can become negative
        long e = (byte) (milstd >> 16) + 1022;

        boolean negative = (m >> 39) == 1;
        long y = 0;

        if (negative) {
            if (m == 0x80_0000_0000L) {
                e++;
            }
            m = -m;
            y = 0x8000_0000_0000_0000L;// this is the sign
        }

        m = m << 14;
        long l = y | (e << 52) | (m & 0xF_FFFF_FFFF_FFFFL);
        return Double.longBitsToDouble(l);
    }

    /**
     * Encodes a double into a MIL-STD 1750A 32 bit number.
     * 
     * If the number to be encoded is too large or too small,
     * the {@link #MAX_FLOAT32_VALUE} respectively {@link #MIN_FLOAT32_VALUE} are returned
     * 
     * @param value
     * @return
     */
    public static int encode32(double value) {
        return (int) (encode48(value) >> 16);
    }

    /**
     * 
     * Decodes a 32 bit MIL-STD 1750A number into a double.
     * If the MIL-STD 1750A number is not normalised (first two bits of opposite value), it returns {@link Double#NaN}
     * 
     * 
     * @param milstd
     * @return
     */
    public static double decode32(int milstd) {
        return decode48(((long) milstd) << 16);
    }

}
