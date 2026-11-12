package org.yamcs.tctm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.yamcs.utils.ByteArrayUtils;

public class CcsdsSeqCountFillerTest {

    static byte[] packet(int apid) {
        byte[] p = new byte[16];
        ByteArrayUtils.encodeUnsignedShort(0x1800 | apid, p, 0);
        ByteArrayUtils.encodeUnsignedShort(0xC000, p, 2); // standalone packet, seq 0
        return p;
    }

    static int seqInHeader(byte[] p) {
        return ByteArrayUtils.decodeUnsignedShort(p, 2) & 0x3FFF;
    }

    static class FakeStore implements SeqCountStore {
        final Map<Integer, Integer> initial;
        final List<Map<Integer, Integer>> saves = new ArrayList<>();
        int loads;

        FakeStore(Map<Integer, Integer> initial) {
            this.initial = initial;
        }

        @Override
        public Map<Integer, Integer> load() {
            loads++;
            return new HashMap<>(initial);
        }

        @Override
        public void save(Map<Integer, Integer> nextSeqCounts) {
            saves.add(new HashMap<>(nextSeqCounts));
        }

        Map<Integer, Integer> last() {
            return saves.get(saves.size() - 1);
        }
    }

    @Test
    public void testNoStoreStartsAtZero() {
        var filler = new CcsdsSeqCountFiller();
        var p = packet(100);
        assertEquals(0, filler.fill(p));
        assertEquals(0, seqInHeader(p));
        assertEquals(1, filler.fill(packet(100)));
        assertEquals(0, filler.fill(packet(200)));
    }

    @Test
    public void testFillKeepsSeqFlags() {
        var filler = new CcsdsSeqCountFiller(new FakeStore(Map.of(100, 5)));
        var p = packet(100);
        filler.fill(p);
        assertEquals(0xC005, ByteArrayUtils.decodeUnsignedShort(p, 2));
    }

    @Test
    public void testSeedFromStore() {
        var store = new FakeStore(Map.of(100, 7));
        var filler = new CcsdsSeqCountFiller(store);
        assertEquals(1, store.loads);
        assertEquals(7, filler.fill(packet(100)));
        assertEquals(0, filler.fill(packet(200)), "APID not in the store starts at 0");
    }

    @Test
    public void testEveryFillPersistsNext() {
        var store = new FakeStore(Map.of());
        var filler = new CcsdsSeqCountFiller(store);
        filler.fill(packet(100));
        assertEquals(Map.of(100, 1), store.last());
        filler.fill(packet(100));
        filler.fill(packet(200));
        assertEquals(3, store.saves.size());
        assertEquals(Map.of(100, 2, 200, 1), store.last());
    }

    @Test
    public void testWrap() {
        var store = new FakeStore(Map.of(100, (1 << 14) - 1));
        var filler = new CcsdsSeqCountFiller(store);
        assertEquals(16383, filler.fill(packet(100)));
        assertEquals(Map.of(100, 0), store.last());
        assertEquals(0, filler.fill(packet(100)));
    }

    @Test
    public void testSetSequencePersists() {
        var store = new FakeStore(Map.of());
        var filler = new CcsdsSeqCountFiller(store);
        filler.setSequence(100, 42);
        assertEquals(Map.of(100, 42), store.last());
        assertEquals(42, filler.fill(packet(100)));
    }

    @Test
    public void testNamedCounterSeededOnce() {
        String name = "test-" + UUID.randomUUID();
        var store1 = new FakeStore(Map.of(100, 10));
        var store2 = new FakeStore(Map.of(100, 999));
        var f1 = new CcsdsSeqCountFiller(name, store1);
        var f2 = new CcsdsSeqCountFiller(name, store2);
        assertEquals(1, store1.loads);
        assertEquals(0, store2.loads, "the shared counter is seeded by its first user only");

        assertEquals(10, f1.fill(packet(100)));
        assertEquals(11, f2.fill(packet(100)));
        assertEquals(Map.of(100, 12), store1.last(), "fills through any link persist to the shared store");
        assertEquals(0, store2.saves.size());
    }
}
