package io.euhedral_execution.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.euhedral_execution.core.control_plane.FragmentControlConfig.BodyCostWeights;
import io.euhedral_execution.core.control_plane.FragmentControlConfig.IdlePolicy;
import org.junit.jupiter.api.Test;

class FragmentDecisionWeightsTest {

    @Test
    void defaultInstance_containsSimplifiedIdleConfiguration() {
        FragmentDecisionWeights weights = FragmentDecisionWeights.DEFAULT;

        assertNotNull(weights);
        assertEquals(BodyCostWeights.DEFAULTS, weights.idleBodyCostWeights());
        assertEquals(IdlePolicy.DEFAULT, weights.idleTimeNs());
    }

    @Test
    void constructor_withNullIdleBodyCostWeights_throwsNullPointerException() {
        assertThrows(NullPointerException.class, () -> new FragmentDecisionWeights(null, IdlePolicy.DEFAULT));
    }

    @Test
    void constructor_withNullIdleTimeNs_throwsNullPointerException() {
        assertThrows(NullPointerException.class, () -> new FragmentDecisionWeights(BodyCostWeights.DEFAULTS, null));
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
    void equalsAndHashCode_verifyRecordContract() {
        FragmentDecisionWeights weights1 = FragmentDecisionWeights.DEFAULT;
        FragmentDecisionWeights weights2 = new FragmentDecisionWeights(BodyCostWeights.DEFAULTS, IdlePolicy.DEFAULT);
        FragmentDecisionWeights weights3 =
                new FragmentDecisionWeights(new BodyCostWeights(10, 20, 30, 40), IdlePolicy.DEFAULT);

        assertEquals(weights1, weights2);
        assertEquals(weights1.hashCode(), weights2.hashCode());
        assertNotNull(weights1.toString());
        assertFalse(weights1.equals(weights3));
    }
}
