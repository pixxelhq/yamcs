package org.yamcs.tctm;

import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yamcs.memento.MementoDb;

import com.google.gson.JsonObject;

/**
 * {@link SeqCountStore} backed by the instance {@link MementoDb}.
 * <p>
 * One memento key per counter: {@code ccsds-seqcount/link/<linkName>} for a link-local counter, or
 * {@code ccsds-seqcount/named/<seqCounterName>} for a named shared counter. The value is a JSON object mapping the
 * APID (as string) to the next sequence count.
 */
public class MementoSeqCountStore implements SeqCountStore {
    private static final Logger log = LoggerFactory.getLogger(MementoSeqCountStore.class);

    public static final String KEY_PREFIX = "ccsds-seqcount/";
    private static final int MAX_APID = 0x7FF;
    private static final int SEQ_MODULO = 1 << 14;

    private final String yamcsInstance;
    private final String key;

    private MementoSeqCountStore(String yamcsInstance, String key) {
        this.yamcsInstance = yamcsInstance;
        this.key = key;
    }

    /** Store for the counter local to the given link. */
    public static MementoSeqCountStore forLink(String yamcsInstance, String linkName) {
        return new MementoSeqCountStore(yamcsInstance, KEY_PREFIX + "link/" + linkName);
    }

    /** Store for the named shared counter (see {@link CcsdsSeqCountFiller#CcsdsSeqCountFiller(String)}). */
    public static MementoSeqCountStore forCounter(String yamcsInstance, String seqCounterName) {
        return new MementoSeqCountStore(yamcsInstance, KEY_PREFIX + "named/" + seqCounterName);
    }

    public String getKey() {
        return key;
    }

    @Override
    public Map<Integer, Integer> load() {
        Map<Integer, Integer> result = new HashMap<>();
        // Fetch the MementoDb on each call: it is dropped from its cache when the instance goes offline.
        var memento = MementoDb.getInstance(yamcsInstance).getJsonObject(key);
        if (memento.isEmpty()) {
            return result;
        }
        for (var entry : memento.get().entrySet()) {
            try {
                int apid = Integer.parseInt(entry.getKey());
                int next = entry.getValue().getAsInt();
                if (apid < 0 || apid > MAX_APID || next < 0 || next >= SEQ_MODULO) {
                    log.warn("{}: ignoring out of range entry apid={} seq={}", key, apid, next);
                    continue;
                }
                result.put(apid, next);
            } catch (RuntimeException e) {
                log.warn("{}: ignoring malformed entry {}={}", key, entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    @Override
    public void save(Map<Integer, Integer> nextSeqCounts) {
        var obj = new JsonObject();
        nextSeqCounts.forEach((apid, next) -> obj.addProperty(Integer.toString(apid), next));
        try {
            MementoDb.getInstance(yamcsInstance).putJsonObject(key, obj);
        } catch (RuntimeException e) {
            // Do not block commanding; the counter restarts from the last saved value after a restart.
            log.error("{}: failed to persist sequence counters", key, e);
        }
    }
}
