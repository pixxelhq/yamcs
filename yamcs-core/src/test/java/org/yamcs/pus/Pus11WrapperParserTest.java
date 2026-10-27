package org.yamcs.pus;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.yamcs.YConfiguration;
import org.yamcs.cmdhistory.CommandHistoryPublisher;
import org.yamcs.commanding.PreparedCommand;
import org.yamcs.events.EventProducerFactory;
import org.yamcs.protobuf.Commanding.CommandHistoryAttribute;
import org.yamcs.protobuf.Commanding.CommandId;
import org.yamcs.tctm.ccsds.time.CucTimeEncoder;
import org.yamcs.time.Instant;
import org.yamcs.time.TimeCorrelationService;
import org.yamcs.utils.ByteArrayUtils;
import org.yamcs.utils.TimeEncoding;
import org.yamcs.utils.ValueHelper;
import org.yamcs.yarch.YarchDatabase;
import org.yamcs.yarch.YarchDatabaseInstance;

/**
 * Parses TC(11,4) wrappers back into their activities (plan §6 step 0). Wrappers are produced by
 * {@link PusCommandPostprocessor} itself, so the parser and the encoder are checked against each other.
 */
public class Pus11WrapperParserTest {
    // 2026-10-22T10:15:30.123Z: the ms part exercises the fine time rounding
    static long scheduleAt;

    @BeforeAll
    static void setUp() {
        TimeEncoding.setUp();
        EventProducerFactory.setMockup(false);
        scheduleAt = TimeEncoding.parse("2026-10-22T10:15:30.123Z");
    }

    @Test
    public void testRoundTripOneByteIds() {
        var pp = newPostprocessor(config(1, 1, explicitPfield(0x2f, "NONE"), true, 0));
        byte[] inner = pp.process(pc(innerCommand(100)));
        byte[] wrapped = newPostprocessor(config(1, 1, explicitPfield(0x2f, "NONE"), true, 0))
                .process(pc(innerCommand(100), scheduleAt(scheduleAt),
                        attr("pus11SubScheduleId", ValueHelper.newValue(3)),
                        attr("pus11GroupId", ValueHelper.newValue(7))));

        var req = new Pus11WrapperParser(pp.getPus11WrapperLayout()).parse(wrapped);

        assertEquals(5, req.apid());
        assertEquals(0, req.sourceId());
        assertEquals(3L, req.subScheduleId());
        assertEquals(1, req.activities().size());
        var act = req.activities().getFirst();
        assertEquals(7L, act.groupId());
        assertEquals(scheduleAt, act.releaseTime());
        assertArrayEquals(inner, act.tc()); // the trailing wrapper CRC is not part of the activity
        assertEquals(100, act.apid());
        assertEquals(0, act.seqCount());
    }

    @Test
    public void testRoundTripTwoByteIds() {
        var cfg = config(2, 2, explicitPfield(0x2f, "NONE"), true, 0x0102);
        var pp = newPostprocessor(cfg);
        byte[] wrapped = pp.process(pc(innerCommand(100), scheduleAt(scheduleAt),
                attr("pus11SubScheduleId", ValueHelper.newValue(0x1234)),
                attr("pus11GroupId", ValueHelper.newValue(0xBEEF))));

        var req = new Pus11WrapperParser(pp.getPus11WrapperLayout()).parse(wrapped);

        assertEquals(0x0102, req.sourceId());
        assertEquals(0x1234L, req.subScheduleId());
        var act = req.activities().getFirst();
        assertEquals(0xBEEFL, act.groupId());
        assertEquals(scheduleAt, act.releaseTime());
        assertEquals(100, act.apid());
    }

    @Test
    public void testDefaultIdsAreReadFromTheBinary() {
        // the config defaults are not published to cmdhist (plan §1.2): the binary is the only source
        var pp = newPostprocessor(config(1, 1, explicitPfield(0x2f, "NONE"), true, 0));
        byte[] wrapped = pp.process(pc(innerCommand(100), scheduleAt(scheduleAt)));

        var req = new Pus11WrapperParser(pp.getPus11WrapperLayout()).parse(wrapped);

        assertEquals(1L, req.subScheduleId()); // config default
        assertEquals(4L, req.activities().getFirst().groupId()); // config default
    }

    @Test
    public void testNoSubScheduleOrGroupField() {
        var pp = newPostprocessor(config(0, 0, explicitPfield(0x2f, "NONE"), false, 0));
        byte[] inner = pp.process(pc(innerCommand(100)));
        byte[] wrapped = newPostprocessor(config(0, 0, explicitPfield(0x2f, "NONE"), false, 0))
                .process(pc(innerCommand(100), scheduleAt(scheduleAt)));

        var req = new Pus11WrapperParser(pp.getPus11WrapperLayout()).parse(wrapped);

        assertNull(req.subScheduleId());
        var act = req.activities().getFirst();
        assertNull(act.groupId());
        assertEquals(scheduleAt, act.releaseTime());
        assertArrayEquals(inner, act.tc());
    }

    @ParameterizedTest
    @ValueSource(strings = { "NONE", "GPS", "J2000", "TAI", "UNIX" })
    public void testReleaseTimeRoundTripsThroughEpoch(String epoch) {
        var pp = newPostprocessor(config(1, 1, explicitPfield(0x2f, epoch), true, 0));
        byte[] wrapped = pp.process(pc(innerCommand(100), scheduleAt(scheduleAt)));

        var act = new Pus11WrapperParser(pp.getPus11WrapperLayout()).parse(wrapped).activities().getFirst();

        assertEquals(scheduleAt, act.releaseTime());
    }

    @Test
    public void testCustomEpoch() {
        var te = new HashMap<>(explicitPfield(0x2f, "CUSTOM"));
        te.put("epochUTC", "2020-01-01T00:00:00Z");
        var pp = newPostprocessor(config(1, 1, te, true, 0));
        byte[] wrapped = pp.process(pc(innerCommand(100), scheduleAt(scheduleAt)));

        var act = new Pus11WrapperParser(pp.getPus11WrapperLayout()).parse(wrapped).activities().getFirst();

        assertEquals(scheduleAt, act.releaseTime());
    }

    @ParameterizedTest
    @ValueSource(ints = { 0x2d, 0x2e, 0x2f }) // 4 coarse bytes + 1, 2 or 3 fine bytes
    public void testImplicitPfieldAndFineTimeWidths(int pfield) {
        var te = Map.<String, Object> of("implicitPfield", true, "pfield", pfield, "epoch", "GPS");
        var pp = newPostprocessor(config(1, 1, te, true, 0));
        long t = TimeEncoding.parse("2026-10-22T10:15:30.000Z"); // 1 fine byte cannot hold every ms exactly
        byte[] wrapped = pp.process(pc(innerCommand(100), scheduleAt(t)));

        var act = new Pus11WrapperParser(pp.getPus11WrapperLayout()).parse(wrapped).activities().getFirst();

        assertEquals(t, act.releaseTime());
    }

    @Test
    public void testReleaseTimeThroughTimeCorrelation() throws Exception {
        var tco = newTco("pus11parser1");
        long t0 = TimeEncoding.parse("2026-10-22T00:00:00Z");
        // 1 tick = 1/65536 s, matching 4 coarse + 2 fine bytes
        tco.addSample(1_000L << 16, Instant.get(t0));
        tco.addSample(1_010L << 16, Instant.get(t0 + 10_000));

        var te = Map.<String, Object> of("implicitPfield", true, "pfield", 0x2e);
        var pp = newPostprocessor(config(1, 1, te, true, 0));
        pp.tcoService = tco;
        long release = t0 + 3_600_000;
        byte[] wrapped = pp.process(pc(innerCommand(100), scheduleAt(release)));

        var act = new Pus11WrapperParser(pp.getPus11WrapperLayout()).parse(wrapped).activities().getFirst();

        assertEquals(release, act.releaseTime());
        tco.stopAsync().awaitTerminated();
    }

    @Test
    public void testReleaseTimeInvalidWithoutTcoCoefficients() throws Exception {
        var tco = newTco("pus11parser2");
        var te = Map.<String, Object> of("implicitPfield", true, "pfield", 0x2e);
        var pp = newPostprocessor(config(1, 1, te, false, 0));
        pp.tcoService = tco;
        byte[] wrapped = handBuiltInsert(pp.getPus11WrapperLayout(), new CucTimeEncoder(0x2e, true),
                new long[] { 123_456L << 16 }, innerCommandWithLength(100, 7, 16));

        var act = new Pus11WrapperParser(pp.getPus11WrapperLayout()).parse(wrapped).activities().getFirst();

        assertEquals(TimeEncoding.INVALID_INSTANT, act.releaseTime());
        tco.stopAsync().awaitTerminated();
    }

    @Test
    public void testManualInsertWithSeveralActivities() {
        var te = Map.<String, Object> of("implicitPfield", true, "pfield", 0x2e, "epoch", "GPS");
        var pp = newPostprocessor(config(1, 1, te, false, 0));
        var layout = pp.getPus11WrapperLayout();
        long t1 = TimeEncoding.parse("2026-10-23T00:00:00Z");
        long t2 = TimeEncoding.parse("2026-10-23T00:00:01.500Z");
        byte[] a = innerCommandWithLength(100, 7, 16);
        byte[] b = innerCommandWithLength(200, 8, 20);
        byte[] wrapped = handBuiltInsertMillis(layout, new CucTimeEncoder(0x2e, true),
                new long[] { layout.shiftToEpoch(t1), layout.shiftToEpoch(t2) }, a, b);

        var req = new Pus11WrapperParser(layout).parse(wrapped);

        assertEquals(42, req.seqCount());
        assertEquals(9, req.sourceId());
        assertEquals(3L, req.subScheduleId());
        assertEquals(2, req.activities().size());
        var first = req.activities().getFirst();
        var second = req.activities().get(1);
        assertEquals(1L, first.groupId());
        assertEquals(2L, second.groupId());
        assertEquals(t1, first.releaseTime());
        assertEquals(t2, second.releaseTime());
        assertArrayEquals(a, first.tc());
        assertArrayEquals(b, second.tc());
        assertEquals(100, first.apid());
        assertEquals(7, first.seqCount());
        assertEquals(200, second.apid());
        assertEquals(8, second.seqCount());
    }

    @Test
    public void testRejectsOtherServices() {
        var pp = newPostprocessor(config(1, 1, explicitPfield(0x2f, "NONE"), true, 0));
        byte[] wrapped = pp.process(pc(innerCommand(100), scheduleAt(scheduleAt)));
        var parser = new Pus11WrapperParser(pp.getPus11WrapperLayout());

        byte[] wrongSubtype = wrapped.clone();
        wrongSubtype[8] = 5;
        assertThrows(IllegalArgumentException.class, () -> parser.parse(wrongSubtype));

        byte[] wrongType = wrapped.clone();
        wrongType[7] = 22;
        assertThrows(IllegalArgumentException.class, () -> parser.parse(wrongType));
    }

    @Test
    public void testRejectsTruncatedPacket() {
        var pp = newPostprocessor(config(1, 1, explicitPfield(0x2f, "NONE"), false, 0));
        byte[] wrapped = pp.process(pc(innerCommand(100), scheduleAt(scheduleAt)));
        var parser = new Pus11WrapperParser(pp.getPus11WrapperLayout());

        assertThrows(IllegalArgumentException.class, () -> parser.parse(Arrays.copyOf(wrapped, wrapped.length - 3)));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(Arrays.copyOf(wrapped, 12)));
    }

    @Test
    public void testLayoutMirrorsConfig() {
        var layout = newPostprocessor(config(2, 1, explicitPfield(0x2f, "GPS"), true, 0)).getPus11WrapperLayout();

        assertEquals(2, layout.subScheduleIdBytes());
        assertEquals(1, layout.groupIdBytes());
        assertTrue(layout.crc());
        assertNull(layout.tcoService());
    }

    // ---- helpers ----

    private static Map<String, Object> explicitPfield(int pfield, String epoch) {
        return Map.of("implicitPfield", false, "pfield", pfield, "epoch", epoch);
    }

    /** a byte width of 0 leaves the sub-schedule / group block out of the config */
    private static YConfiguration config(int subScheduleBytes, int groupBytes, Map<String, Object> timeEncoding,
            boolean crc, int sourceId) {
        Map<String, Object> pus11 = new HashMap<>();
        pus11.put("sourceId", sourceId);
        if (subScheduleBytes > 0) {
            pus11.put("subScheduleId", Map.of("bytes", subScheduleBytes, "default", 1));
        }
        if (groupBytes > 0) {
            pus11.put("groupId", Map.of("bytes", groupBytes, "default", 4));
        }
        Map<String, Object> m = new HashMap<>();
        m.put("timeEncoding", timeEncoding);
        m.put("pus11Apid", 5);
        m.put("pus11", pus11);
        if (crc) {
            m.put("errorDetection", Map.of("type", "CRC-16-CCIIT"));
        }
        return YConfiguration.wrap(m);
    }

    private static PusCommandPostprocessor newPostprocessor(YConfiguration config) {
        var pp = new PusCommandPostprocessor();
        pp.setCommandHistoryPublisher(new NullCommandHistoryPublisher());
        pp.init(null, config, null);
        return pp;
    }

    private static TimeCorrelationService newTco(String name) throws Exception {
        String instance = "pus11-parser-test";
        YarchDatabaseInstance ydb = YarchDatabase.getInstance(instance);
        String table = TimeCorrelationService.TABLE_NAME + name;
        if (ydb.getTable(table) != null) {
            ydb.dropTable(table);
        }
        var tco = new TimeCorrelationService();
        tco.init(instance, name, YConfiguration.wrap(Map.of("numSamples", 2)));
        tco.startAsync().awaitRunning();
        return tco;
    }

    /** minimal 16 byte CCSDS TC packet; the postprocessor fills in length and seq count */
    private static byte[] innerCommand(int apid) {
        byte[] b = new byte[16];
        b[0] = (byte) (0x18 | (apid >> 8));
        b[1] = (byte) apid;
        b[2] = (byte) 0xc0;
        return b;
    }

    /** a complete inner TC, as it would sit in a manually built TC(11,4) */
    private static byte[] innerCommandWithLength(int apid, int seqCount, int length) {
        byte[] b = new byte[length];
        b[0] = (byte) (0x18 | (apid >> 8));
        b[1] = (byte) apid;
        ByteArrayUtils.encodeUnsignedShort(0xC000 | seqCount, b, 2);
        ByteArrayUtils.encodeUnsignedShort(length - 7, b, 4);
        for (int i = 6; i < length; i++) {
            b[i] = (byte) (apid + i);
        }
        return b;
    }

    /**
     * TC(11,4) as an operator would send it via /PUS11/INSERT_ACTIVITIES: APID 5, seq 42, source 9, sub-schedule 3,
     * activity i in group i+1. Release times are raw ticks.
     */
    private static byte[] handBuiltInsert(Pus11WrapperLayout layout, CucTimeEncoder enc, long[] obts,
            byte[]... tcs) {
        return handBuiltInsert(layout, enc, obts, true, tcs);
    }

    /** same as {@link #handBuiltInsert}, with release times in ms since the configured epoch */
    private static byte[] handBuiltInsertMillis(Pus11WrapperLayout layout, CucTimeEncoder enc, long[] millis,
            byte[]... tcs) {
        return handBuiltInsert(layout, enc, millis, false, tcs);
    }

    private static byte[] handBuiltInsert(Pus11WrapperLayout layout, CucTimeEncoder enc, long[] times, boolean raw,
            byte[]... tcs) {
        byte[] buf = new byte[1024];
        buf[0] = 0x18;
        buf[1] = 5;
        ByteArrayUtils.encodeUnsignedShort(0xC000 | 42, buf, 2);
        int o = 6;
        buf[o++] = 0x2D;
        buf[o++] = 11;
        buf[o++] = 4;
        ByteArrayUtils.encodeUnsignedShort(9, buf, o);
        o += 2;
        o += PusCommandPostprocessor.encodeUnsigned(3, buf, o, layout.subScheduleIdBytes());
        buf[o++] = (byte) tcs.length;
        for (int i = 0; i < tcs.length; i++) {
            o += PusCommandPostprocessor.encodeUnsigned(i + 1, buf, o, layout.groupIdBytes());
            o += raw ? enc.encodeRaw(times[i], buf, o) : enc.encode(times[i], buf, o);
            System.arraycopy(tcs[i], 0, buf, o, tcs[i].length);
            o += tcs[i].length;
        }
        byte[] tc = Arrays.copyOf(buf, o);
        ByteArrayUtils.encodeUnsignedShort(tc.length - 7, tc, 4);
        return tc;
    }

    private static CommandHistoryAttribute scheduleAt(long t) {
        return attr("pus11ScheduleAt", ValueHelper.newTimestampValue(t));
    }

    private static PreparedCommand pc(byte[] binary, CommandHistoryAttribute... attrs) {
        var p = new PreparedCommand(binary);
        for (var a : attrs) {
            p.addAttribute(a);
        }
        return p;
    }

    private static CommandHistoryAttribute attr(String name, org.yamcs.protobuf.Yamcs.Value v) {
        return CommandHistoryAttribute.newBuilder().setName(name).setValue(v).build();
    }

    private static class NullCommandHistoryPublisher implements CommandHistoryPublisher {
        @Override
        public void publish(CommandId cmdId, String key, String value) {
        }

        @Override
        public void publish(CommandId cmdId, String key, int value) {
        }

        @Override
        public void publish(CommandId cmdId, String key, long value) {
        }

        @Override
        public void publish(CommandId cmdId, String key, byte[] binary) {
        }

        @Override
        public void addCommand(PreparedCommand pc) {
        }
    }
}
