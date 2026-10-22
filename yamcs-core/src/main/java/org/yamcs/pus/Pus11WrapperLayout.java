package org.yamcs.pus;

import org.yamcs.tctm.AbstractPacketPreprocessor.TimeEpochs;
import org.yamcs.tctm.ccsds.time.CucTimeEncoder;
import org.yamcs.time.TimeCorrelationService;
import org.yamcs.utils.TimeEncoding;

/**
 * How a {@link PusCommandPostprocessor} lays out the TC(11,4) wrappers it builds, so that the wrappers can be parsed
 * back (see {@link Pus11WrapperParser}) without re-reading the link configuration.
 * <p>
 * The release time is encoded either as raw on-board time obtained from {@code tcoService}, or, without time
 * correlation, as ground time shifted to {@code epoch}.
 *
 * @param subScheduleIdBytes
 *            width of the sub-schedule id field, 0 if the field is not present
 * @param groupIdBytes
 *            width of the group id field of each activity, 0 if the field is not present
 * @param crc
 *            whether the wrapper ends with a 2 byte CRC
 * @param customEpoch
 *            used only if {@code epoch} is CUSTOM; a Yamcs instant if {@code customEpochIncludeLeapSecond}, else a
 *            UNIX time
 * @param tcoService
 *            null if the release time is not encoded as on-board time
 */
public record Pus11WrapperLayout(int subScheduleIdBytes, int groupIdBytes, boolean crc, CucTimeEncoder timeEncoder,
        TimeEpochs epoch, long customEpoch, boolean customEpochIncludeLeapSecond,
        TimeCorrelationService tcoService) {

    /**
     * Number of bytes of the release time field, including the explicit P-field if any
     */
    public int timeLength() {
        return timeEncoder.getEncodedLength();
    }

    /**
     * Reads the release time field at {@code offset} as raw ticks: coarse and fine time packed into one long, exactly
     * as encoded. Filters and shifts are evaluated on this value, never on ground time.
     */
    public long decodeObt(byte[] buf, int offset) {
        int n = timeEncoder.getBasicTimeBytes() + timeEncoder.getFractionalTimeBytes();
        int o = offset + timeLength() - n; // skip the explicit P-field
        long t = 0;
        for (int i = 0; i < n; i++) {
            t = (t << 8) | (buf[o + i] & 0xFF);
        }
        return t;
    }

    /**
     * Converts raw release ticks to ground time.
     *
     * @return the Yamcs instant, or {@link TimeEncoding#INVALID_INSTANT} if the time correlation has no coefficients
     */
    public long toGroundTime(long obt) {
        if (tcoService != null) {
            return tcoService.getTime(obt).getMillis();
        }
        int shift = 8 * timeEncoder.getFractionalTimeBytes();
        long coarse = obt >>> shift;
        long fine = obt & ((1L << shift) - 1);
        // the encoder truncates ms to ticks; rounding the ticks up gives the ms back whenever a tick is shorter than 1 ms
        long millis = coarse * 1000 + ((fine * 1000 + (1L << shift) - 1) >> shift);
        return shiftFromEpoch(millis);
    }

    public long shiftToEpoch(long t) {
        return switch (epoch) {
        case GPS -> TimeEncoding.toGpsTimeMillisec(t);
        case J2000 -> TimeEncoding.toJ2000Millisec(t);
        case TAI -> TimeEncoding.toTaiMillisec(t);
        case UNIX -> TimeEncoding.toUnixMillisec(t);
        case CUSTOM -> customEpochIncludeLeapSecond ? t - customEpoch : TimeEncoding.toUnixMillisec(t) - customEpoch;
        case NONE -> t;
        };
    }

    public long shiftFromEpoch(long t) {
        return switch (epoch) {
        case GPS -> TimeEncoding.fromGpsMillisec(t);
        case J2000 -> TimeEncoding.fromJ2000Millisec(t);
        case TAI -> TimeEncoding.fromTaiMillisec(t);
        case UNIX -> TimeEncoding.fromUnixMillisec(t);
        case CUSTOM -> customEpochIncludeLeapSecond ? customEpoch + t : TimeEncoding.fromUnixMillisec(customEpoch + t);
        case NONE -> t;
        };
    }
}
