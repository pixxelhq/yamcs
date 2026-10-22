package org.yamcs.pus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.yamcs.tctm.CcsdsPacket;
import org.yamcs.utils.ByteArrayUtils;

/**
 * Parses a TC(11,4) "insert activities into the time-based schedule" back into its activities. Works both for the
 * single-activity wrappers built by {@link PusCommandPostprocessor} and for manual inserts with N activities, as long as
 * they follow the link's {@link Pus11WrapperLayout}.
 * <p>
 * Layout: {@code hdr(6) | pus(3) | sourceId(2) | subScheduleId(0..8) | N(1) | N x [groupId(0..8) | releaseTime |
 * TC] | CRC?}
 */
public class Pus11WrapperParser {
    static final int SOURCE_ID_OFFSET = 9;

    /**
     * @param apid
     *            request ID of the TC(11,4) itself
     * @param subScheduleId
     *            null if the layout has no sub-schedule id field
     */
    public record InsertRequest(int apid, int seqCount, int sourceId, Long subScheduleId, List<Activity> activities) {
    }

    /**
     * @param groupId
     *            null if the layout has no group id field
     * @param releaseObt
     *            release time as raw ticks, exactly as encoded
     * @param releaseTime
     *            release time as ground time, {@link org.yamcs.utils.TimeEncoding#INVALID_INSTANT} if it cannot be
     *            computed (no time correlation coefficients)
     * @param tc
     *            the embedded command
     */
    public record Activity(Long groupId, long releaseObt, long releaseTime, byte[] tc) {
        /** APID of the embedded command, part of the request ID its release is reported with */
        public int apid() {
            return CcsdsPacket.getAPID(tc);
        }

        public int seqCount() {
            return CcsdsPacket.getSequenceCount(tc);
        }
    }

    final Pus11WrapperLayout layout;

    public Pus11WrapperParser(Pus11WrapperLayout layout) {
        this.layout = layout;
    }

    /**
     * @throws IllegalArgumentException
     *             if the packet is not a TC(11,4) or is shorter than its content
     */
    public InsertRequest parse(byte[] tc) {
        if (tc.length < SOURCE_ID_OFFSET || tc[7] != 11 || tc[8] != 4) {
            throw new IllegalArgumentException("Not a TC(11,4)");
        }
        int end = ByteArrayUtils.decodeUnsignedShort(tc, 4) + 7;
        if (end > tc.length) {
            throw new IllegalArgumentException("Packet length " + end + " exceeds the " + tc.length + " bytes available");
        }
        if (layout.crc()) {
            end -= 2;
        }
        int o = SOURCE_ID_OFFSET;
        require(o + 2 + layout.subScheduleIdBytes() + 1, end);
        int sourceId = ByteArrayUtils.decodeUnsignedShort(tc, o);
        o += 2;
        Long subScheduleId = decodeId(tc, o, layout.subScheduleIdBytes());
        o += layout.subScheduleIdBytes();
        int n = tc[o++] & 0xFF;

        List<Activity> activities = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            require(o + layout.groupIdBytes() + layout.timeLength() + 6, end);
            Long groupId = decodeId(tc, o, layout.groupIdBytes());
            o += layout.groupIdBytes();
            long obt = layout.decodeObt(tc, o);
            o += layout.timeLength();
            int tcLength = ByteArrayUtils.decodeUnsignedShort(tc, o + 4) + 7;
            require(o + tcLength, end);
            activities.add(new Activity(groupId, obt, layout.toGroundTime(obt), Arrays.copyOfRange(tc, o, o + tcLength)));
            o += tcLength;
        }
        return new InsertRequest(CcsdsPacket.getAPID(tc), CcsdsPacket.getSequenceCount(tc), sourceId, subScheduleId,
                activities);
    }

    private static void require(int needed, int end) {
        if (needed > end) {
            throw new IllegalArgumentException("TC(11,4) truncated: needs " + needed + " bytes, has " + end);
        }
    }

    /** big-endian unsigned of {@code nbytes}, the inverse of {@link PusCommandPostprocessor#encodeUnsigned} */
    private static Long decodeId(byte[] buf, int offset, int nbytes) {
        if (nbytes == 0) {
            return null;
        }
        long v = 0;
        for (int i = 0; i < nbytes; i++) {
            v = (v << 8) | (buf[offset + i] & 0xFF);
        }
        return v;
    }
}
