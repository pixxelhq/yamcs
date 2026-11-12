package org.yamcs.tctm;

import java.util.Map;

/**
 * Persists the per-APID CCSDS sequence counters of a {@link CcsdsSeqCountFiller}, so they survive a restart.
 * <p>
 * Values are always the <b>next</b> sequence count to be used for an APID.
 */
public interface SeqCountStore {

    /**
     * @return the persisted next sequence count per APID; empty if nothing was persisted yet
     */
    Map<Integer, Integer> load();

    /**
     * Persists the current next sequence count of all APIDs of one counter.
     * <p>
     * Called while the counter is locked, so calls for the same counter never interleave.
     */
    void save(Map<Integer, Integer> nextSeqCounts);
}
