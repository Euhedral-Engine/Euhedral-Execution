package io.euhedral_execution.core.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.euhedral_execution.core.control_plane.FragmentObserver;
import java.io.File;
import java.util.BitSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductionIdlePolicyTest {
    @Test
    void productionDefinitionMatchesEveryFrozenFieldExactly() throws Exception {
        var mapper = new ObjectMapper();
        var artifact = mapper.readTree(new File(System.getProperty("euhedral.test.frozenCachePolicy")));
        assertEquals("policy-158a61afee6653cbbfde", artifact.get("policyId").asText());
        var frozen = mapper.treeToValue(artifact.get("runtimeTimingFunction"), IdleTimingFunction.class);
        var runtimeOnly = mapper.readValue(
                new File(System.getProperty("euhedral.test.frozenCacheRuntime")), IdleTimingFunction.class);
        // Record equality compares every bound/reference/normalizer and every exact Double value.
        assertEquals(frozen, runtimeOnly);
        assertEquals(frozen, IdlePolicy.DEFAULT_FUNCTION);
        assertSame(IdlePolicy.DEFAULT_FUNCTION, IdlePolicy.DEFAULT.function());
        for (var values : List.of(frozen.parkCoefficients(), frozen.halfLifeCoefficients())) {
            assertThrows(UnsupportedOperationException.class, () -> values.set(0, 0.0));
        }
    }

    @Test
    void representativeEvaluatorOutputsMatchFrozenFunction() {
        var function = IdlePolicy.DEFAULT_FUNCTION;
        // Expected values evaluated independently from the full frozen artifact.
        double[][] inputs = {
            {0, 1.0 / 7, 0}, {.8, 1.0 / 15, 96}, {1, 1.0 / 23, 576}, {.5, 1, 200}, {1, 4, Math.expm1(16)}
        };
        long[][] expected = {{297992, 335481}, {296288, 330401}, {294495, 328870}, {169593, 398313}, {25728, 726394}};
        for (int i = 0; i < inputs.length; i++) {
            assertEquals(expected[i][0], function.parkNanos(inputs[i][0], inputs[i][1], inputs[i][2], 17));
            assertEquals(expected[i][1], function.halfLifeNanos(inputs[i][0], inputs[i][1], inputs[i][2], 19));
        }
        assertEquals(17, function.parkNanos(Double.NaN, 0, 0, 17));
        assertEquals(19, function.halfLifeNanos(0, 5, 0, 19));
    }

    @Test
    void defaultsAndClonesShareProductionDefinitionWhileExplicitTimingRemainsConfigurable() {
        var defaults = FragmentConfig.ofDefaults();
        assertSame(IdlePolicy.DEFAULT, defaults.idlePolicy());
        assertSame(IdlePolicy.DEFAULT, FragmentConfig.ofDefaults("test", null).idlePolicy());
        assertSame(
                IdlePolicy.DEFAULT,
                defaults.clone(new CloneConfig("test", 0, new BitSet())).idlePolicy());
        var suppliedFunction =
                IdleTimingFunctionTest.function(List.of(0.0, 0.0, 0.0, 0.0), List.of(0.0, 0.0, 0.0, 0.0));
        var supplied = new IdlePolicy(32000, 6000000, suppliedFunction);
        var config =
                FragmentConfig.ofBenchmark(mock(FragmentObserver.class), FragmentDecisionWeights.DEFAULT, supplied);
        assertSame(supplied, config.idlePolicy());
        assertSame(suppliedFunction, config.idlePolicy().function());
        assertSame(
                supplied, config.clone(new CloneConfig("test", 0, new BitSet())).idlePolicy());
        assertNull(new IdlePolicy(32000, 6000000).function());
        assertNull(new IdlePolicy(32000, 6000000, null).function());
    }
}
