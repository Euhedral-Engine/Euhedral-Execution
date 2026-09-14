package io.euhedral_execution.benchmarks.cfd.config;

/// Payload bytes are exact; JVM overhead and retained frame/mask storage are conservative estimates.
public record MemoryEstimate(
        long populationBytes, long auxiliaryBytes, long totalBytes, long budgetBytes, boolean arrayIndexable) {
    /// HotSpot commonly reserves a few elements below the language's signed-int length limit.
    public static final long MAX_ARRAY_LENGTH = Integer.MAX_VALUE - 8L;

    public MemoryEstimate {
        Checks.require(populationBytes > 0 && auxiliaryBytes >= 0 && budgetBytes > 0, "invalid memory estimate");
        Checks.require(totalBytes == Math.addExact(populationBytes, auxiliaryBytes), "inconsistent memory total");
    }

    public static long defaultBudget(long maxHeapBytes, long usedHeapBytes) {
        Checks.require(
                maxHeapBytes > 0 && usedHeapBytes >= 0 && usedHeapBytes <= maxHeapBytes,
                "no available heap for CFD memory budget");
        return Math.max(1, (maxHeapBytes - usedHeapBytes) / 2);
    }

    public static MemoryEstimate estimate(SimulationConfig config, long defaultBudgetBytes) {
        GridShape grid = config.grid();
        long cells = grid.cellCount();
        try {
            long populations = Math.multiplyExact(304L, cells);
            /// Byte mask + int obstacle ID per cell, descriptors/scratch/reductions per brick,
            /// fixed JVM/array overhead, and a bounded streaming export buffer when enabled.
            long auxiliary = Math.addExact(
                    Math.multiplyExact(5L, cells),
                    Math.multiplyExact(768L, grid.brickCount(config.execution().brick())));
            var geometry = config.geometry();
            long primitives = Math.addExact(
                    Math.addExact(
                            (long) geometry.boxes().size(), geometry.spheres().size()),
                    Math.addExact(geometry.cylinders().size(), geometry.meshes().size()));
            auxiliary = Math.addExact(auxiliary, Math.multiplyExact(256L, primitives));
            /// Per-range force slots plus pending/completed driver force storage, indexed by compact IDs.
            auxiliary = Math.addExact(
                    auxiliary,
                    Math.multiplyExact(
                            Math.multiplyExact(24L, primitives),
                            Math.addExact(grid.brickCount(config.execution().brick()), 2)));
            auxiliary = Math.addExact(auxiliary, 1_048_576L);
            if (config.output().exportEverySteps() > 0) auxiliary = Math.addExact(auxiliary, 131_072L);
            long budget = config.memoryLimitBytes() == null ? defaultBudgetBytes : config.memoryLimitBytes();
            return new MemoryEstimate(
                    populations, auxiliary, Math.addExact(populations, auxiliary), budget, cells <= MAX_ARRAY_LENGTH);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("memory requirement overflows for grid " + grid, e);
        }
    }

    public void requireAllocatable(GridShape grid) {
        String detail = "grid " + grid + ": estimated requirement=" + totalBytes + " bytes (populations="
                + populationBytes + ", auxiliary=" + auxiliaryBytes + "), budget=" + budgetBytes + " bytes";
        Checks.require(arrayIndexable, detail + "; direction arrays exceed Java array limit " + MAX_ARRAY_LENGTH);
        Checks.require(totalBytes <= budgetBytes, detail + "; memory budget exceeded");
    }
}
