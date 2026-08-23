package io.euhedral_execution.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.euhedral_execution.core.control_plane.FragmentControlConfig;
import io.euhedral_execution.core.control_plane.FragmentControlConfig.BodyCostWeights;
import io.euhedral_execution.core.control_plane.FragmentControlConfig.ContentionThresholds;
import io.euhedral_execution.core.control_plane.FragmentControlConfig.ExecutionPath;
import io.euhedral_execution.core.control_plane.FragmentControlConfig.ExecutionPolicy;
import io.euhedral_execution.core.control_plane.FragmentControlConfig.IdlePolicy;
import java.util.List;
import org.junit.jupiter.api.Test;

class FragmentDecisionWeightsTest {

    @Test
    void defaultInstance_containsNonNullFieldsAndValidStructure() {
        FragmentDecisionWeights weights = FragmentDecisionWeights.DEFAULT;

        assertNotNull(weights);
        assertNotNull(weights.idleContentionThresholds());
        assertNotNull(weights.idleBodyCostWeights());
        assertNotNull(weights.idleTimeNs());
        assertNotNull(weights.execContentionThresholds());
        assertNotNull(weights.execBodyCostWeights());
        assertNotNull(weights.executionPolicies());

        assertEquals(
                FragmentControlConfig.IDLE_WEIGHT_SETS,
                weights.idleBodyCostWeights().size());
        assertEquals(
                FragmentControlConfig.EXEC_WEIGHT_SETS,
                weights.execBodyCostWeights().size());
        assertEquals(FragmentControlConfig.POLICY_COUNT, weights.idleTimeNs().size());
        assertEquals(
                FragmentControlConfig.POLICY_COUNT, weights.executionPolicies().size());
    }

    @Test
    void constructor_withNullIdleContentionThresholds_throwsNullPointerException() {
        assertThrows(
                NullPointerException.class,
                () -> new FragmentDecisionWeights(
                        null,
                        BodyCostWeights.DEFAULTS,
                        IdlePolicy.DEFAULT,
                        ContentionThresholds.EXEC_DEFAULTS,
                        BodyCostWeights.EXEC_DEFAULTS,
                        ExecutionPolicy.DEFAULT));
    }

    @Test
    void constructor_withNullIdleBodyCostWeights_throwsNullPointerException() {
        assertThrows(
                NullPointerException.class,
                () -> new FragmentDecisionWeights(
                        ContentionThresholds.DEFAULTS,
                        null,
                        IdlePolicy.DEFAULT,
                        ContentionThresholds.EXEC_DEFAULTS,
                        BodyCostWeights.EXEC_DEFAULTS,
                        ExecutionPolicy.DEFAULT));
    }

    @Test
    void constructor_withNullIdleTimeNs_throwsNullPointerException() {
        assertThrows(
                NullPointerException.class,
                () -> new FragmentDecisionWeights(
                        ContentionThresholds.DEFAULTS,
                        BodyCostWeights.DEFAULTS,
                        null,
                        ContentionThresholds.EXEC_DEFAULTS,
                        BodyCostWeights.EXEC_DEFAULTS,
                        ExecutionPolicy.DEFAULT));
    }

    @Test
    void constructor_withNullExecContentionThresholds_throwsNullPointerException() {
        assertThrows(
                NullPointerException.class,
                () -> new FragmentDecisionWeights(
                        ContentionThresholds.DEFAULTS,
                        BodyCostWeights.DEFAULTS,
                        IdlePolicy.DEFAULT,
                        null,
                        BodyCostWeights.EXEC_DEFAULTS,
                        ExecutionPolicy.DEFAULT));
    }

    @Test
    void constructor_withNullExecBodyCostWeights_throwsNullPointerException() {
        assertThrows(
                NullPointerException.class,
                () -> new FragmentDecisionWeights(
                        ContentionThresholds.DEFAULTS,
                        BodyCostWeights.DEFAULTS,
                        IdlePolicy.DEFAULT,
                        ContentionThresholds.EXEC_DEFAULTS,
                        null,
                        ExecutionPolicy.DEFAULT));
    }

    @Test
    void constructor_withNullExecutionPolicies_throwsNullPointerException() {
        assertThrows(
                NullPointerException.class,
                () -> new FragmentDecisionWeights(
                        ContentionThresholds.DEFAULTS,
                        BodyCostWeights.DEFAULTS,
                        IdlePolicy.DEFAULT,
                        ContentionThresholds.EXEC_DEFAULTS,
                        BodyCostWeights.EXEC_DEFAULTS,
                        null));
    }

    @Test
    void jsonRoundtrip_serializesAndDeserializesCorrectly() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        String json = mapper.writeValueAsString(FragmentDecisionWeights.DEFAULT);
        assertNotNull(json);
        System.out.println(json);

        FragmentDecisionWeights deserialized = mapper.readValue(json, FragmentDecisionWeights.class);

        assertEquals(FragmentDecisionWeights.DEFAULT, deserialized);
        assertEquals(FragmentDecisionWeights.DEFAULT.hashCode(), deserialized.hashCode());
    }

    @Test
    void equalsAndHashCode_verifyRecordContract() {
        FragmentDecisionWeights weights1 = FragmentDecisionWeights.DEFAULT;
        FragmentDecisionWeights weights2 = new FragmentDecisionWeights(
                ContentionThresholds.DEFAULTS,
                BodyCostWeights.DEFAULTS,
                IdlePolicy.DEFAULT,
                ContentionThresholds.EXEC_DEFAULTS,
                BodyCostWeights.EXEC_DEFAULTS,
                ExecutionPolicy.DEFAULT);

        FragmentDecisionWeights weights3 = new FragmentDecisionWeights(
                new ContentionThresholds(100_000L, 200_000L, 300_000L, 400_000L),
                BodyCostWeights.DEFAULTS,
                IdlePolicy.DEFAULT,
                ContentionThresholds.EXEC_DEFAULTS,
                BodyCostWeights.EXEC_DEFAULTS,
                List.of(
                        new ExecutionPolicy(
                                ExecutionPath.STAGED,
                                ExecutionPath.STAGED,
                                ExecutionPath.STAGED,
                                ExecutionPath.STAGED,
                                ExecutionPath.STAGED),
                        new ExecutionPolicy(
                                ExecutionPath.STAGED,
                                ExecutionPath.STAGED,
                                ExecutionPath.STAGED,
                                ExecutionPath.STAGED,
                                ExecutionPath.STAGED),
                        new ExecutionPolicy(
                                ExecutionPath.STAGED,
                                ExecutionPath.STAGED,
                                ExecutionPath.STAGED,
                                ExecutionPath.STAGED,
                                ExecutionPath.STAGED),
                        new ExecutionPolicy(
                                ExecutionPath.STAGED,
                                ExecutionPath.STAGED,
                                ExecutionPath.STAGED,
                                ExecutionPath.STAGED,
                                ExecutionPath.STAGED),
                        new ExecutionPolicy(
                                ExecutionPath.STAGED,
                                ExecutionPath.STAGED,
                                ExecutionPath.STAGED,
                                ExecutionPath.STAGED,
                                ExecutionPath.STAGED)));

        assertEquals(weights1, weights2);
        assertEquals(weights1.hashCode(), weights2.hashCode());
        assertNotNull(weights1.toString());
        assertFalse(weights1.equals(weights3));
    }
}
