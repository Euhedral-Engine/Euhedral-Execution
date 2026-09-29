package io.euhedral_execution.hardware_utils.topology.internal;

@FunctionalInterface
public interface TopologyProvider {

    TopologyInput collect() throws Exception;
}
