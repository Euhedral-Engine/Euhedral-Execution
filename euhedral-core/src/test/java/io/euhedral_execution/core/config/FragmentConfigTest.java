package io.euhedral_execution.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.euhedral_execution.core.control_plane.FragmentObserver;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.BitSet;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class FragmentConfigTest {

    @Test
    void ofDefaults_createsExpectedDefaultState() {
        FragmentConfig config = FragmentConfig.ofDefaults();

        assertNull(config.cloneConfig());
        assertNotNull(config.cacheConfig());
        assertEquals(FragmentDecisionWeights.DEFAULT, config.decisionWeights());
        assertNull(config.observer());
        assertEquals(4_096L, config.maxBatchSize());
        assertFalse(config.benchmarkMode());
        assertNull(config.metricPrefix());
        assertNull(config.registry());
        assertEquals(-1, config.getCore());
    }

    @Test
    void ofDefaults_withMetricPrefixAndRegistry_propagatesFields() {
        MeterRegistry registry = new SimpleMeterRegistry();
        String prefix = "test.metric.prefix";

        FragmentConfig config = FragmentConfig.ofDefaults(prefix, registry);

        assertEquals(prefix, config.metricPrefix());
        assertSame(registry, config.registry());
        assertNotNull(config.cacheConfig());
    }

    @Test
    void ofBenchmark_withValidArguments_createsBenchmarkConfig() {
        FragmentObserver observer = Mockito.mock(FragmentObserver.class);

        FragmentConfig config = FragmentConfig.ofBenchmark(observer, FragmentDecisionWeights.DEFAULT);

        assertTrue(config.benchmarkMode());
        assertSame(observer, config.observer());
        assertEquals(FragmentDecisionWeights.DEFAULT, config.decisionWeights());
        assertEquals(4_096L, config.maxBatchSize());
        assertNull(config.cloneConfig());
    }

    @Test
    void ofBenchmark_withNullObserver_throwsNullPointerException() {
        assertThrows(
                NullPointerException.class, () -> FragmentConfig.ofBenchmark(null, FragmentDecisionWeights.DEFAULT));
    }

    @Test
    void ofBenchmark_withNullDecisionWeights_throwsNullPointerException() {
        FragmentObserver observer = Mockito.mock(FragmentObserver.class);
        assertThrows(NullPointerException.class, () -> FragmentConfig.ofBenchmark(observer, null));
    }

    @Test
    void constructor_withNullCacheConfig_throwsNullPointerException() {
        assertThrows(
                NullPointerException.class,
                () -> new FragmentConfig(null, null, FragmentDecisionWeights.DEFAULT, null, 100, false, null, null));
    }

    @Test
    void constructor_withNullDecisionWeights_throwsNullPointerException() {
        assertThrows(
                NullPointerException.class,
                () -> new FragmentConfig(null, CacheConfig.ofDefaults(), null, null, 100, false, null, null));
    }

    @Test
    void constructor_withInvalidMaxBatchSize_throwsIllegalArgumentException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new FragmentConfig(
                        null, CacheConfig.ofDefaults(), FragmentDecisionWeights.DEFAULT, null, 0, false, null, null));

        assertThrows(
                IllegalArgumentException.class,
                () -> new FragmentConfig(
                        null, CacheConfig.ofDefaults(), FragmentDecisionWeights.DEFAULT, null, -1, false, null, null));

        assertThrows(
                IllegalArgumentException.class,
                () -> new FragmentConfig(
                        null,
                        CacheConfig.ofDefaults(),
                        FragmentDecisionWeights.DEFAULT,
                        null,
                        -100,
                        false,
                        null,
                        null));
    }

    @Test
    void constructor_withBenchmarkModeTrueAndNullObserver_throwsIllegalArgumentException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new FragmentConfig(
                        null, CacheConfig.ofDefaults(), FragmentDecisionWeights.DEFAULT, null, 100, true, null, null));
    }

    @Test
    void clone_withCloneConfig_clonesCacheAndSetsCloneConfig() {
        BitSet bitSet = new BitSet();
        bitSet.set(3);
        CloneConfig cloneConfig = new CloneConfig("shard-0", 3, bitSet);
        FragmentConfig original = FragmentConfig.ofDefaults();

        FragmentConfig cloned = original.clone(cloneConfig);

        assertEquals(cloneConfig, cloned.cloneConfig());
        assertEquals(3, cloned.getCore());
        assertNotNull(cloned.cacheConfig());
        assertEquals(original.maxBatchSize(), cloned.maxBatchSize());
        assertEquals(original.decisionWeights(), cloned.decisionWeights());
    }

    @Test
    void getCore_whenCloneConfigIsNull_returnsNegativeOne() {
        FragmentConfig config = FragmentConfig.ofDefaults();
        assertEquals(-1, config.getCore());
    }

    @Test
    void equalsAndHashCode_verifyRecordContract() {
        BitSet bitSet = new BitSet();
        bitSet.set(1);
        FragmentConfig config1 = FragmentConfig.ofDefaults();
        FragmentConfig config2 = FragmentConfig.ofDefaults();
        FragmentConfig config3 = new FragmentConfig(
                new CloneConfig("shard-0", 1, bitSet),
                CacheConfig.ofDefaults(),
                FragmentDecisionWeights.DEFAULT,
                null,
                1_024,
                false,
                null,
                null);

        assertEquals(config1, config2);
        assertEquals(config1.hashCode(), config2.hashCode());
        assertNotNull(config1.toString());
        assertNotEquals(config1, config3);
    }
}
