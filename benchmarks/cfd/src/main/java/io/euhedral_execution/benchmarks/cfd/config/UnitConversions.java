package io.euhedral_execution.benchmarks.cfd.config;

/// Physical units per lattice unit. Factors are resolved and checked once with the configuration.
public record UnitConversions(
        double lengthScale,
        double timeScale,
        double densityScale,
        double velocityScale,
        double accelerationScale,
        double viscosityScale,
        double pressureScale,
        double forceScale) {
    public UnitConversions {
        for (double scale : new double[] {
            lengthScale,
            timeScale,
            densityScale,
            velocityScale,
            accelerationScale,
            viscosityScale,
            pressureScale,
            forceScale
        }) Checks.positive(scale, "unit conversion scale");
        Checks.require(
                velocityScale == lengthScale / timeScale
                        && accelerationScale == velocityScale / timeScale
                        && viscosityScale == velocityScale * lengthScale
                        && pressureScale == densityScale * velocityScale * velocityScale
                        && forceScale == pressureScale * lengthScale * lengthScale,
                "inconsistent unit conversion scales");
    }

    public static UnitConversions of(double dx, double dt, double rhoScale) {
        Checks.positive(dx, "voxel width");
        Checks.positive(dt, "time step");
        Checks.positive(rhoScale, "density scale");
        double velocity = Checks.positive(dx / dt, "velocity scale");
        double pressure = Checks.positive(rhoScale * velocity * velocity, "pressure scale");
        return new UnitConversions(
                dx, dt, rhoScale, velocity, velocity / dt, velocity * dx, pressure, pressure * dx * dx);
    }

    private static double checked(double input, double output) {
        Checks.finite(input, "conversion input");
        Checks.finite(output, "conversion result");
        Checks.require(input == 0 || output != 0, "nonzero unit conversion underflows");
        return output;
    }

    public double lengthToLattice(double value) {
        return checked(value, value / lengthScale);
    }

    public double lengthToPhysical(double value) {
        return checked(value, value * lengthScale);
    }

    public double timeToLattice(double value) {
        return checked(value, value / timeScale);
    }

    public double timeToPhysical(double value) {
        return checked(value, value * timeScale);
    }

    public double densityToLattice(double value) {
        return checked(value, value / densityScale);
    }

    public double densityToPhysical(double value) {
        return checked(value, value * densityScale);
    }

    public double velocityToLattice(double value) {
        return checked(value, value / velocityScale);
    }

    public double velocityToPhysical(double value) {
        return checked(value, value * velocityScale);
    }

    public double accelerationToLattice(double value) {
        return checked(value, value / accelerationScale);
    }

    public double accelerationToPhysical(double value) {
        return checked(value, value * accelerationScale);
    }

    public double viscosityToLattice(double value) {
        return checked(value, value / viscosityScale);
    }

    public double viscosityToPhysical(double value) {
        return checked(value, value * viscosityScale);
    }

    public double pressureToLattice(double value) {
        return checked(value, value / pressureScale);
    }

    public double pressureToPhysical(double value) {
        return checked(value, value * pressureScale);
    }

    public double forceToLattice(double value) {
        return checked(value, value / forceScale);
    }

    public double forceToPhysical(double value) {
        return checked(value, value * forceScale);
    }

    public Vector3 velocityToLattice(Vector3 value) {
        return new Vector3(velocityToLattice(value.x()), velocityToLattice(value.y()), velocityToLattice(value.z()));
    }

    public Vector3 accelerationToLattice(Vector3 value) {
        return new Vector3(
                accelerationToLattice(value.x()), accelerationToLattice(value.y()), accelerationToLattice(value.z()));
    }
}
