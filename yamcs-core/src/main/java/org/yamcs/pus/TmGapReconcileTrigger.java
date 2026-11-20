package org.yamcs.pus;

/**
 * Detects the start of a pass (acquisition of signal) from a silence in the TM stream (plan §5 Q1): the first packet
 * after more than {@code gap} milliseconds without any packet. The first packet seen after a start is always an
 * acquisition, which reconciles the model after a restart.
 * <p>
 * Not thread safe on its own: call from one thread, or under the caller's lock.
 */
public class TmGapReconcileTrigger {
    private final long gap;
    private long lastPacket = -1;

    /**
     * @param gap
     *            silence in milliseconds that marks the end of a pass; must exceed the longest in-pass dropout and be
     *            shorter than the shortest gap between passes
     */
    public TmGapReconcileTrigger(long gap) {
        this.gap = gap;
    }

    /**
     * @param now
     *            the reception time of the packet, in milliseconds
     * @return true if the packet starts a pass
     */
    public synchronized boolean onPacket(long now) {
        boolean aos = lastPacket < 0 || now - lastPacket > gap;
        lastPacket = now;
        return aos;
    }
}
