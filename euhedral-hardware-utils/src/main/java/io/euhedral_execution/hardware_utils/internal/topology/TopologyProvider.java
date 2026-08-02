package io.euhedral_execution.hardware_utils.internal.topology;

@FunctionalInterface
public interface TopologyProvider {

    TopologyInput collect() throws Exception;
}
