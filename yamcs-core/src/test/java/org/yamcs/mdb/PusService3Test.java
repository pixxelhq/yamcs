package org.yamcs.mdb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.yamcs.ErrorInCommand;
import org.yamcs.ProcessorConfig;
import org.yamcs.YConfiguration;
import org.yamcs.commanding.ArgumentValue;
import org.yamcs.mdb.MetaCommandProcessor.CommandBuildResult;
import org.yamcs.utils.StringConverter;
import org.yamcs.xtce.Argument;
import org.yamcs.xtce.MetaCommand;

public class PusService3Test {
    private Mdb mdb;
    private MetaCommandProcessor processor;

    @BeforeEach
    public void setup() throws Exception {
        YConfiguration.setupTest(null);
        mdb = MdbFactory.createInstanceByConfig("PusService3Config");
        processor = new MetaCommandProcessor(new ProcessorData("test", mdb, new ProcessorConfig()));
    }

    /**
     * TC[3,38] – Create a parameter functional reporting definition.
     *
     * Packet layout:
     *   funcReportingDefID (uint16) = 5        → 0005
     *   N1                 (uint16) = 1        → 0001
     *   Group 1:
     *     appProcessID (uint16) = 10           → 000A
     *     N2           (uint16) = 2            → 0002
     *     Def 1: structType=1(0001), structID=100(0064), periodicStatus=1(0001), interval=1000(000003E8)
     *     Def 2: structType=2(0002), structID=200(00C8), periodicStatus=0(0000), interval=2000(000007D0)
     */
    @Test
    public void testTC_3_38_Encoding() throws ErrorInCommand {
        MetaCommand mc = mdb.getMetaCommand("/PUS3/CreateFuncReportingDef");

        Map<String, Object> def1 = new LinkedHashMap<>();
        def1.put("structType",     1);
        def1.put("structID",       100);
        def1.put("periodicStatus", 1);
        def1.put("interval",       1000);

        Map<String, Object> def2 = new LinkedHashMap<>();
        def2.put("structType",     2);
        def2.put("structID",       200);
        def2.put("periodicStatus", 0);
        def2.put("interval",       2000);

        Map<String, Object> group = new LinkedHashMap<>();
        group.put("appProcessID", 10);
        group.put("definitions",  Arrays.asList(def1, def2));

        Map<String, Object> args = new HashMap<>();
        args.put("funcReportingDefID", 5);
        args.put("groups", List.of(group));

        CommandBuildResult result = processor.buildCommand(mc, args, 0);
        printResult("TC[3,38] CreateFuncReportingDef", result);

        // funcReportingDefID=5   → 0005
        // N1=1                   → 0001
        // appProcessID=10        → 000A
        // N2=2                   → 0002
        // structType=1           → 0001
        // structID=100           → 0064
        // periodicStatus=1       → 0001
        // interval=1000          → 000003E8
        // structType=2           → 0002
        // structID=200           → 00C8
        // periodicStatus=0       → 0000
        // interval=2000          → 000007D0
        assertEquals("00050001000A0002000100640001000003E8000200C80000000007D0",
                StringConverter.arrayToHexString(result.getCmdPacket()));
    }

    /**
     * TC[3,39] – Delete parameter functional reporting definitions.
     *
     * Packet layout:
     *   N   (uint16) = 3         → 0003
     *   IDs: 5(0005), 10(000A), 15(000F)
     */
    @Test
    public void testTC_3_39_Encoding() throws ErrorInCommand {
        MetaCommand mc = mdb.getMetaCommand("/PUS3/DeleteFuncReportingDefs");

        Map<String, Object> args = new HashMap<>();
        args.put("IDs", Arrays.asList(5, 10, 15));

        CommandBuildResult result = processor.buildCommand(mc, args, 0);
        printResult("TC[3,39] DeleteFuncReportingDefs", result);

        // N=3 → 0003, IDs: 5→0005, 10→000A, 15→000F
        assertEquals("00030005000A000F", StringConverter.arrayToHexString(result.getCmdPacket()));
    }

    /**
     * TC[3,40] – Report housekeeping parameter report structures.
     *
     * Packet layout:
     *   N   (uint16) = 3         → 0003
     *   IDs: 100(0064), 200(00C8), 300(012C)
     */
    @Test
    public void testTC_3_40_Encoding() throws ErrorInCommand {
        MetaCommand mc = mdb.getMetaCommand("/PUS3/ReportDefinitions");

        Map<String, Object> args = new HashMap<>();
        args.put("IDs", Arrays.asList(100, 200, 300));

        CommandBuildResult result = processor.buildCommand(mc, args, 0);
        printResult("TC[3,40] ReportDefinitions", result);

        // N=3 (0003), IDs=100(0064), 200(00C8), 300(012C)
        assertEquals("0003006400C8012C", StringConverter.arrayToHexString(result.getCmdPacket()));
    }

    /**
     * TC[3,42] – Add definitions to an existing housekeeping report structure.
     *
     * Packet layout:
     *   N1=2 → 0002
     *   Group 1: PID=1(0001), N2=1(0001), Def: ID=10(000A), Type=1(0001), SID=101(0065), Stat=1(0001), Int=5000(00001388)
     *   Group 2: PID=2(0002), N2=2(0002), Def: ID=20(0014)…, Def: ID=21(0015)…
     */
    @Test
    public void testTC_3_42_Encoding() throws ErrorInCommand {
        MetaCommand mc = mdb.getMetaCommand("/PUS3/AddDefinitions");

        // Group 1
        Map<String, Object> g1 = new LinkedHashMap<>();
        g1.put("appProcessID", 1);
        g1.put("definitions", List.of(
                createDef(10, 1, 101, 1, 5000)
        ));

        // Group 2
        Map<String, Object> g2 = new LinkedHashMap<>();
        g2.put("appProcessID", 2);
        g2.put("definitions", Arrays.asList(
            createDef(20, 1, 201, 0, 10000),
            createDef(21, 1, 202, 1, 10000)
        ));

        Map<String, Object> args = new HashMap<>();
        args.put("groups", Arrays.asList(g1, g2));

        CommandBuildResult result = processor.buildCommand(mc, args, 0);
        printResult("TC[3,42] AddDefinitions", result);

        // Structure: N1(2) | G1[PID(1), N2(1), Def1] | G2[PID(2), N2(2), Def2, Def3]
        // N1=2 -> 0002
        // G1: PID=1(0001), N2=1(0001), Def: ID=10(000A), Type=1(0001), SID=101(0065), Stat=1(0001), Int=5000(00001388)
        String expected = "0002" +
                          "00010001000A00010065000100001388" +
                          "000200020014000100C90000000027100015000100CA000100002710";
        assertEquals(expected, StringConverter.arrayToHexString(result.getCmdPacket()));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private Map<String, Object> createDef(int id, int type, int sid, int stat, int interval) {
        Map<String, Object> def = new LinkedHashMap<>();
        def.put("defID",      id);
        def.put("structType", type);
        def.put("structID",   sid);
        def.put("status",     stat);
        def.put("interval",   interval);
        return def;
    }

    /**
     * Prints the encoded packet (hex) and every resolved argument value to
     * stdout so the full CommandBuildResult is visible in the test output.
     */
    private static void printResult(String label, CommandBuildResult result) {
        System.out.println("=== " + label + " ===");
        System.out.println("  Packet (hex): " + StringConverter.arrayToHexString(result.getCmdPacket()));
        System.out.println("  Resolved args:");
        Map<Argument, ArgumentValue> argMap = result.getArgs();
        argMap.forEach((arg, av) ->
            System.out.println("    " + av.toString())
        );
        System.out.println();
    }
}
