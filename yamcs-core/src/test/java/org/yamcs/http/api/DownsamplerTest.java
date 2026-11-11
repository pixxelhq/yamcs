package org.yamcs.http.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yamcs.http.api.Downsampler.Sample;
import org.yamcs.parameter.ParameterValue;
import org.yamcs.parameter.ValueArray;
import org.yamcs.parameterarchive.ParameterValueArray;
import org.yamcs.protobuf.Yamcs.Value.Type;
import org.yamcs.utils.ValueUtility;

public class DownsamplerTest {

    @Test
    public void testSampling() {
        Downsampler sampler = new Downsampler(1, 10, 3);

        List<Sample> samples = sampler.collect();
        assertEquals(0, samples.size());

        sampler.process(1, 5, -1);

        samples = sampler.collect();
        assertEquals(1, samples.size());
        assertEquals(5, samples.get(0).avg, 1e-10);
        assertEquals(1, samples.get(0).n);

        // Add to same bucket
        sampler.process(2, 10, -1);
        assertEquals(1, samples.size());
        assertEquals((5 + 10) / 2., samples.get(0).avg, 1e-10);
        assertEquals(2, samples.get(0).n);

        // Add to same bucket
        sampler.process(3, 7, -1);
        samples = sampler.collect();
        assertEquals(1, samples.size());
        assertEquals((5 + 10 + 7) / 3., samples.get(0).avg, 1e-10);
        assertEquals(3, samples.get(0).n);

        // Due to flooring, leads to a new bucket
        sampler.process(4, 2, -1);
        samples = sampler.collect();
        assertEquals(2, samples.size());

        Sample sample0 = samples.get(0);
        assertEquals((5 + 10 + 7) / 3., sample0.avg, 1e-10);
        assertEquals(5, sample0.min, 1e-10);
        assertEquals(10, sample0.max, 1e-10);

        Sample sample1 = samples.get(1);
        assertEquals(2, sample1.avg, 1e-10);
        assertEquals(2, sample1.min, 1e-10);
        assertEquals(2, sample1.max, 1e-10);
    }

    @Test
    public void testSamplingTooMany() {
        Downsampler sampler = new Downsampler(1, 2, 3);
        sampler.process(1, 1, -1);
        sampler.process(2, 2, -1);
        sampler.process(2, 2.3, -1);
        List<Sample> samples = sampler.collect();
        assertEquals(1, samples.size());
    }

    @Test
    public void testSamplingInvalid() {
        assertThrows(IllegalArgumentException.class, () -> {
            new Downsampler(2, 1, 3);
        });
    }
    @Test
    public void testCategoricalKeepsLastValue() {
        Downsampler sampler = new Downsampler(1, 10, 3);
        sampler.enableCategoricalMode(null);
        sampler.process(1, 0, -1);
        sampler.process(2, 4, -1);
        sampler.process(3, 2, -1);

        List<Sample> samples = sampler.collect();
        assertEquals(1, samples.size());
        Sample s = samples.get(0);
        assertEquals(2, s.avg, 1e-10);
        assertEquals(2, s.min, 1e-10);
        assertEquals(2, s.max, 1e-10);
        assertEquals(3, s.n);
    }

    @Test
    public void testCategoricalEnumAndBooleanValues() {
        Downsampler sampler = new Downsampler(0, 30, 3);
        sampler.enableCategoricalMode(Map.of("OFF", 0, "ON", 1));

        sampler.process(pval(1, ValueUtility.getEnumeratedValue(1, "ON")));
        // Ordinal not present in the MDB enumeration: must not be plotted
        sampler.process(pval(11, ValueUtility.getEnumeratedValue(99, "UNDEF")));
        sampler.process(pval(21, ValueUtility.getBooleanValue(true)));

        List<Sample> samples = sampler.collect();
        assertEquals(3, samples.size());
        assertEquals(1, samples.get(0).avg, 1e-10);
        assertTrue(Double.isNaN(samples.get(1).avg));
        assertEquals(1, samples.get(2).avg, 1e-10);
    }

    @Test
    public void testCategoricalColumnarEnumLabels() {
        Downsampler sampler = new Downsampler(0, 30, 3);
        sampler.enableCategoricalMode(Map.of("IDLE", 0, "ARMED", 3, "LOST", 200));

        long[] timestamps = { 1, 2, 11, 21 };
        ValueArray engValues = new ValueArray(Type.ENUMERATED,
                new Object[] { "IDLE", "LOST", "ARMED", "UNKNOWN" });
        sampler.accept(new ParameterValueArray(timestamps, engValues, null, null));

        List<Sample> samples = sampler.collect();
        assertEquals(3, samples.size());
        assertEquals(200, samples.get(0).avg, 1e-10);
        assertEquals(3, samples.get(1).avg, 1e-10);
        assertTrue(Double.isNaN(samples.get(2).avg));
    }

    @Test
    public void testColumnarBoolean() {
        Downsampler sampler = new Downsampler(0, 30, 3);
        BitSet bits = new BitSet();
        bits.set(1);
        ValueArray engValues = new ValueArray(bits, 2);
        sampler.accept(new ParameterValueArray(new long[] { 1, 11 }, engValues, null, null));

        List<Sample> samples = sampler.collect();
        assertEquals(2, samples.size());
        assertEquals(0, samples.get(0).avg, 1e-10);
        assertEquals(1, samples.get(1).avg, 1e-10);
    }

    private static ParameterValue pval(long time, org.yamcs.parameter.Value engValue) {
        ParameterValue pv = new ParameterValue("/test/p");
        pv.setGenerationTime(time);
        pv.setEngValue(engValue);
        return pv;
    }
}
