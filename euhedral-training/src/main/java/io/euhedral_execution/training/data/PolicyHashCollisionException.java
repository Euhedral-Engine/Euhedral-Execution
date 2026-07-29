package io.euhedral_execution.training.data;

public final class PolicyHashCollisionException extends IllegalArgumentException {

    public PolicyHashCollisionException(PolicyId id) {
        super("Different policy vectors share " + id.canonical());
    }
}
