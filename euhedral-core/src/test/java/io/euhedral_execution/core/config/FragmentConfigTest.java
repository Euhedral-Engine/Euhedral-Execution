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
    void cacheTimingDefaultsValidationAndCompatibility() {
        assertEquals(new IdlePolicy(15_000L, 1_000_000L, IdlePolicy.DEFAULT_FUNCTION), IdlePolicy.DEFAULT);
        assertEquals(IdlePolicy.DEFAULT, FragmentConfig.ofDefaults().idlePolicy());
        assertEquals(0L, new IdlePolicy(0L, 1L).idleParkNs());
        assertThrows(IllegalArgumentException.class, () -> new IdlePolicy(-1L, 1L));
        assertThrows(IllegalArgumentException.class, () -> new IdlePolicy(1L, 0L));
        assertThrows(IllegalArgumentException.class, () -> new IdlePolicy(1L, -1L));
        FragmentConfig legacy =
                new FragmentConfig(null, CacheConfig.ofDefaults(), null, 100, false, 7_000_000L, false, null, null);
        assertEquals(new IdlePolicy(15_000L, 7_000_000L), legacy.idlePolicy());
        assertThrows(
                NullPointerException.class,
                () -> new FragmentConfig(null, CacheConfig.ofDefaults(), null, 100, false, null, false, null, null));
    }

    @Test
    void benchmarkClonePreservesBothTimingValues() {
        IdlePolicy timing = new IdlePolicy(43_000L, 7_000_000L);
        FragmentConfig config = FragmentConfig.ofBenchmark(Mockito.mock(FragmentObserver.class), timing);
        BitSet cpus = new BitSet();
        cpus.set(3);
        assertSame(timing, config.clone(new CloneConfig("timing", 3, cpus)).idlePolicy());
    }

    @Test
    void ofDefaults_createsExpectedDefaultState() {
        FragmentConfig config = FragmentConfig.ofDefaults();

        assertNull(config.cloneConfig());
        assertNotNull(config.cacheConfig());
        assertNull(config.observer());
        assertEquals(4_096L, config.maxBatchSize());
        assertEquals(FragmentConfig.DEFAULT_CONTENTION_HALF_LIFE_NANOS, config.contentionHalfLifeNanos());
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

        FragmentConfig config = FragmentConfig.ofBenchmark(observer);

        assertTrue(config.benchmarkMode());
        assertSame(observer, config.observer());
        assertEquals(4_096L, config.maxBatchSize());
        assertNull(config.cloneConfig());
    }

    @Test
    void ofBenchmark_withNullObserver_throwsNullPointerException() {
        assertThrows(NullPointerException.class, () -> FragmentConfig.ofBenchmark(null));
    }

    @Test
    void ofBenchmark_withNullIdlePolicy_throwsNullPointerException() {
        FragmentObserver observer = Mockito.mock(FragmentObserver.class);
        assertThrows(NullPointerException.class, () -> FragmentConfig.ofBenchmark(observer, null));
    }

    @Test
    void constructor_withNullCacheConfig_throwsNullPointerException() {
        assertThrows(
                NullPointerException.class,
                () -> new FragmentConfig(
                        null,
                        null,
                        null,
                        100,
                        false,
                        FragmentConfig.DEFAULT_CONTENTION_HALF_LIFE_NANOS,
                        false,
                        null,
                        null));
    }

    @Test
    void constructor_withInvalidMaxBatchSize_throwsIllegalArgumentException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new FragmentConfig(
                        null,
                        CacheConfig.ofDefaults(),
                        null,
                        0,
                        false,
                        FragmentConfig.DEFAULT_CONTENTION_HALF_LIFE_NANOS,
                        false,
                        null,
                        null));

        assertThrows(
                IllegalArgumentException.class,
                () -> new FragmentConfig(
                        null,
                        CacheConfig.ofDefaults(),
                        null,
                        -1,
                        false,
                        FragmentConfig.DEFAULT_CONTENTION_HALF_LIFE_NANOS,
                        false,
                        null,
                        null));

        assertThrows(
                IllegalArgumentException.class,
                () -> new FragmentConfig(
                        null,
                        CacheConfig.ofDefaults(),
                        null,
                        -100,
                        false,
                        FragmentConfig.DEFAULT_CONTENTION_HALF_LIFE_NANOS,
                        false,
                        null,
                        null));
    }

    @Test
    void constructor_withInvalidContentionHalfLife_throwsIllegalArgumentException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new FragmentConfig(null, CacheConfig.ofDefaults(), null, 100, false, 0L, false, null, null));
    }

    @Test
    void constructor_withBenchmarkModeTrueAndNullObserver_throwsIllegalArgumentException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new FragmentConfig(
                        null,
                        CacheConfig.ofDefaults(),
                        null,
                        100,
                        false,
                        FragmentConfig.DEFAULT_CONTENTION_HALF_LIFE_NANOS,
                        true,
                        null,
                        null));
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
        assertEquals(original.contentionHalfLifeNanos(), cloned.contentionHalfLifeNanos());
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
                null,
                1_024,
                false,
                FragmentConfig.DEFAULT_CONTENTION_HALF_LIFE_NANOS,
                false,
                null,
                null);

        assertEquals(config1, config2);
        assertEquals(config1.hashCode(), config2.hashCode());
        assertNotNull(config1.toString());
        assertNotEquals(config1, config3);
    }
}
