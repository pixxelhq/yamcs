package org.yamcs.tctm;

import java.util.HashMap;
import java.util.Map;

import org.yamcs.utils.ByteArrayUtils;

/**
 * Fills in the CCSDS primary header sequence count per APID.
 * <p>
 * By default each instance maintains its own independent counters, so each link has its own sequence count per APID.
 * <p>
 * A name can be supplied via {@link #CcsdsSeqCountFiller(String)} to create a named shared counter: all instances
 * constructed with the same name share the same counters. This is useful when multiple links must present a continuous
 * sequence to the target.
 * <p>
 * With a {@link SeqCountStore} the counters are seeded from the store when they are created, and every change is
 * written to the store before the sequence count is returned, so they survive a restart.
 */
public class CcsdsSeqCountFiller {
    private static final int SEQ_MODULO = 1 << 14;

    // Named shared counters - keyed by name then by APID
    private static final Map<String, Counters> namedSeqCounts = new HashMap<>();

    // Per-instance (or shared) counters
    private final Counters counters;

    /** Creates an instance-local counter that is not shared with any other link. */
    public CcsdsSeqCountFiller() {
        this((SeqCountStore) null);
    }

    /**
     * Creates an instance-local counter, seeded from and persisted to the given store.
     *
     * @param store
     *            may be null, in which case the counters are kept in memory only
     */
    public CcsdsSeqCountFiller(SeqCountStore store) {
        this.counters = new Counters(store);
    }

    /**
     * Creates a named counter shared with all other instances that use the same name.
     *
     * @param name
     *            shared counter name; instances with the same name share the same per-APID counters
     */
    public CcsdsSeqCountFiller(String name) {
        this(name, null);
    }

    /**
     * Creates a named counter shared with all other instances that use the same name.
     * <p>
     * The store is only used by the first instance that creates the counter: it seeds the counter and persists all
     * later changes, whichever instance makes them.
     *
     * @param name
     *            shared counter name; instances with the same name share the same per-APID counters
     * @param store
     *            may be null, in which case the counters are kept in memory only
     */
    public CcsdsSeqCountFiller(String name, SeqCountStore store) {
        synchronized (namedSeqCounts) {
            this.counters = namedSeqCounts.computeIfAbsent(name, k -> new Counters(store));
        }
    }

    private int getSeqCount(int apid) {
        synchronized (counters) {
            int seqCount = counters.map.getOrDefault(apid, 0);
            var nextSeqCount = (seqCount + 1) % SEQ_MODULO;
            counters.map.put(apid, nextSeqCount);
            counters.persist();
            return seqCount;
        }
    }

    /**
     * Generates a sequence count and fills it in the CCSDS primary header.
     *
     * @param packet
     * @return the generated sequence count
     */
    public int fill(byte[] packet) {
        int apidseqcount = ByteArrayUtils.decodeInt(packet, 0);

        int apid = (apidseqcount >> 16) & 0x07FF;
        int seqFlags = apidseqcount >>> 14;

        int seqCount = getSeqCount(apid);

        ByteArrayUtils.encodeUnsignedShort((short) ((seqFlags << 14) | seqCount), packet, 2);

        return seqCount;
    }

    public void setSequence(int apid, int seqCount) {
        synchronized (counters) {
            counters.map.put(apid, seqCount);
            counters.persist();
        }
    }

    private static class Counters {
        final Map<Integer, Integer> map = new HashMap<>();
        final SeqCountStore store;

        Counters(SeqCountStore store) {
            this.store = store;
            if (store != null) {
                map.putAll(store.load());
            }
        }

        void persist() {
            if (store != null) {
                store.save(new HashMap<>(map));
            }
        }
    }
}
