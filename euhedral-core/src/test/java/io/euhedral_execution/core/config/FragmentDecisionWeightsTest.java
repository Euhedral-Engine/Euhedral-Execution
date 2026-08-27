package io.euhedral_execution.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.euhedral_execution.core.config.FragmentDecisionWeights.BodyCostWeights;
import io.euhedral_execution.core.config.FragmentDecisionWeights.IdlePolicy;
import io.euhedral_execution.core.config.FragmentDecisionWeights.ParetoWeights;
import org.junit.jupiter.api.Test;

class FragmentDecisionWeightsTest {

    @Test
    void defaultInstance_containsSimplifiedIdleConfiguration() {
        FragmentDecisionWeights weights = FragmentDecisionWeights.DEFAULT;

        assertNotNull(weights);
        assertEquals(FragmentDecisionWeights.BodyCostWeights.DEFAULTS, weights.idleBodyCostWeights());
        assertEquals(FragmentDecisionWeights.IdlePolicy.DEFAULT, weights.idleTimeNs());
        assertEquals(new IdlePolicy(50_000, 0, 0, 0, 0), weights.idleTimeNs());
        assertEquals(
                FragmentDecisionWeights.DEFAULT_BODY_COST_DIRECT_THRESHOLD_WEIGHT,
                weights.bodyCostDirectThresholdWeight());
    }

    @Test
    void constructor_withNullIdleBodyCostWeights_throwsNullPointerException() {
        assertThrows(
                NullPointerException.class,
                () -> new FragmentDecisionWeights(
                        null, FragmentDecisionWeights.IdlePolicy.DEFAULT, ParetoWeights.DEFAULT));
    }

    @Test
    void constructor_withNullIdleTimeNs_throwsNullPointerException() {
        assertThrows(
                NullPointerException.class,
                () -> new FragmentDecisionWeights(
                        FragmentDecisionWeights.BodyCostWeights.DEFAULTS, null, ParetoWeights.DEFAULT));
    }

    @Test
    void constructor_withNegativeBodyCostDirectThresholdWeight_throwsIllegalArgumentException() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new FragmentDecisionWeights(
                        BodyCostWeights.DEFAULTS, IdlePolicy.DEFAULT, -1, ParetoWeights.DEFAULT));
    }

    @Test
    void jsonRoundtrip_serializesOnlyActiveConfiguration() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        String json = mapper.writeValueAsString(FragmentDecisionWeights.DEFAULT);
        FragmentDecisionWeights deserialized = mapper.readValue(json, FragmentDecisionWeights.class);

        assertEquals(FragmentDecisionWeights.DEFAULT, deserialized);
        assertEquals(FragmentDecisionWeights.DEFAULT.hashCode(), deserialized.hashCode());
        assertFalse(json.contains("contentionThresholds"));
        assertFalse(json.contains("executionPolicies"));
    }

    @Test
    void jsonWithoutParetoWeightsUsesCurrentDefault() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        FragmentDecisionWeights weights = mapper.readValue("""
                {
                  "idleBodyCostWeights": {"xs": 1, "s": 2, "m": 3, "h": 4},
                  "idleTimeNs": {"xsPark": 5, "sPark": 6, "mPark": 7, "hPark": 8, "xhPark": 9}
                }
                """, FragmentDecisionWeights.class);

        assertEquals(ParetoWeights.DEFAULT, weights.paretoWeights());
        assertEquals(
                FragmentDecisionWeights.DEFAULT_BODY_COST_DIRECT_THRESHOLD_WEIGHT,
                weights.bodyCostDirectThresholdWeight());
    }

    @Test
    void equalsAndHashCode_verifyRecordContract() {
        FragmentDecisionWeights weights1 = FragmentDecisionWeights.DEFAULT;
        FragmentDecisionWeights weights2 = new FragmentDecisionWeights(
                FragmentDecisionWeights.BodyCostWeights.DEFAULTS,
                FragmentDecisionWeights.IdlePolicy.DEFAULT,
                ParetoWeights.DEFAULT);
        FragmentDecisionWeights weights3 = new FragmentDecisionWeights(
                new BodyCostWeights(10, 20, 30, 40), FragmentDecisionWeights.IdlePolicy.DEFAULT, ParetoWeights.DEFAULT);

        assertEquals(weights1, weights2);
        assertEquals(weights1.hashCode(), weights2.hashCode());
        assertNotNull(weights1.toString());
        assertNotEquals(weights1, weights3);
    }
}
