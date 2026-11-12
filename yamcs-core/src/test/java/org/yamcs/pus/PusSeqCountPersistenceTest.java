package org.yamcs.pus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.yamcs.ConfigurationException;
import org.yamcs.YConfiguration;
import org.yamcs.memento.MementoDb;
import org.yamcs.tctm.CcsdsSeqCountFiller;
import org.yamcs.tctm.Link;
import org.yamcs.utils.ByteArrayUtils;
import org.yamcs.yarch.YarchTestCase;

/**
 * {@code persistSeqCount}: the CCSDS sequence counters survive a restart through the instance memento (plan step 0b).
 * <p>
 * One test method only: {@link MementoDb} caches its database per instance name, and {@link YarchTestCase} recreates
 * the database for each method under the same name.
 */
public class PusSeqCountPersistenceTest extends YarchTestCase {

    static byte[] packet(int apid) {
        byte[] p = new byte[16];
        ByteArrayUtils.encodeUnsignedShort(0x1800 | apid, p, 0);
        ByteArrayUtils.encodeUnsignedShort(0xC000, p, 2);
        return p;
    }

    static int fill(CcsdsSeqCountFiller filler, int apid) {
        return filler.fill(packet(apid));
    }

    @Test
    public void testPersistSeqCount() {
        Link link = mock(Link.class);
        when(link.getName()).thenReturn("tc_sim");
        var persist = YConfiguration.wrap(Map.of("persistSeqCount", true));

        // link-local counter: 3 TCs on APID 100, 1 on APID 200, then "restart"
        var f1 = PusCommandPostprocessor.createSeqFiller(instance, persist, link);
        assertEquals(0, fill(f1, 100));
        assertEquals(1, fill(f1, 100));
        assertEquals(2, fill(f1, 100));
        assertEquals(0, fill(f1, 200));

        var memento = MementoDb.getInstance(instance).getJsonObject("ccsds-seqcount/link/tc_sim").get();
        assertEquals(3, memento.get("100").getAsInt());
        assertEquals(1, memento.get("200").getAsInt());

        var f2 = PusCommandPostprocessor.createSeqFiller(instance, persist, link);
        assertEquals(3, fill(f2, 100), "continues after restart");
        assertEquals(1, fill(f2, 200));

        // change-seq-count survives a restart too
        f2.setSequence(100, 500);
        var f3 = PusCommandPostprocessor.createSeqFiller(instance, persist, link);
        assertEquals(500, fill(f3, 100));

        // default: not persisted, starts at 0
        var f4 = PusCommandPostprocessor.createSeqFiller(instance, YConfiguration.emptyConfig(), link);
        assertEquals(0, fill(f4, 100));

        // named counter: own key, independent of the link-local one
        String name = "sat-" + UUID.randomUUID();
        var named = YConfiguration.wrap(Map.of("persistSeqCount", true, "seqCounterName", name));
        var f5 = PusCommandPostprocessor.createSeqFiller(instance, named, link);
        assertEquals(0, fill(f5, 100));
        assertEquals(1, MementoDb.getInstance(instance).getJsonObject("ccsds-seqcount/named/" + name).get()
                .get("100").getAsInt());

        // no link and no counter name: nothing to key the memento on
        assertThrows(ConfigurationException.class,
                () -> PusCommandPostprocessor.createSeqFiller(instance, persist, null));
        assertThrows(ConfigurationException.class,
                () -> PusCommandPostprocessor.createSeqFiller(null, named, link));
    }
}
