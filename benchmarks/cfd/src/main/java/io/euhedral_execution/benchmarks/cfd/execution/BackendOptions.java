package io.euhedral_execution.benchmarks.cfd.execution;

import java.util.Set;

/// Optional execution.backendOptions settings are retained in replay configurations.
public record BackendOptions(
        String backend, Integer workers, Object sources, String cpus, Boolean affinity, Long shutdownTimeoutMillis) {
    public static final BackendOptions DEFAULT = new BackendOptions(null, null, null, null, null, null);

    public BackendOptions {
        backend = backend == null ? "serial" : backend;
        if (!Set.of("serial", "euhedral", "fjp", "static").contains(backend)) {
            throw new IllegalArgumentException("backend must be serial, euhedral, fjp or static");
        }
        if (workers != null && workers <= 0) {
            throw new IllegalArgumentException("workers must be positive");
        }
        sources = sources == null ? "workers" : sources;
        if (!(sources.equals("workers") || sources instanceof Integer number && number > 0)) {
            throw new IllegalArgumentException("sources must be a positive integer or workers");
        }
        if (backend.equals("serial")
                && (workers != null && workers != 1 || sources instanceof Integer number && number != 1)) {
            throw new IllegalArgumentException("serial requires one worker and one source");
        }
        if (!backend.equals("serial") && !backend.equals("euhedral") && !sources.equals("workers")) {
            throw new IllegalArgumentException("sources applies only to Euhedral execution");
        }
        affinity = affinity == null ? false : affinity;
        shutdownTimeoutMillis = shutdownTimeoutMillis == null ? 5_000L : shutdownTimeoutMillis;
        if (shutdownTimeoutMillis <= 0 || shutdownTimeoutMillis > Long.MAX_VALUE / 1_000_000) {
            throw new IllegalArgumentException("shutdownTimeoutMillis must be positive and fit nanoseconds");
        }
        if (cpus != null) {
            if (cpus.isBlank()) {
                throw new IllegalArgumentException("cpus must be a comma-separated CPU list");
            }
            for (String cpu : cpus.split(",", -1)) {
                if (Integer.parseInt(cpu) < 0) {
                    throw new IllegalArgumentException("CPU IDs must be non-negative");
                }
            }
        }
    }

    public int sourceCount(int workers) {
        if (backend.equals("serial")) {
            return 1;
        }
        if (!backend.equals("euhedral")) {
            return 0;
        }
        return sources instanceof Integer count ? count : workers;
    }
}
