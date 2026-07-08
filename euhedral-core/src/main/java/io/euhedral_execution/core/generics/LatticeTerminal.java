package io.euhedral_execution.core.generics;

/// Defines the bottom of a chain of lattices
public interface LatticeTerminal {
    void addUpstream(LatticeSource upstream);
}
