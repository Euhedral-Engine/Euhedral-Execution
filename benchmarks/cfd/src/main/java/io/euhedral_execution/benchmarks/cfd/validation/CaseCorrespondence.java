package io.euhedral_execution.benchmarks.cfd.validation;

import io.euhedral_execution.benchmarks.cfd.config.CfdConfiguration;
import io.euhedral_execution.benchmarks.cfd.config.Vector3;

/// Different numerical resolutions must still describe the same physical input problem.
final class CaseCorrespondence {
    private CaseCorrespondence() {}

    static String difference(CfdConfiguration candidate, CfdConfiguration reference) {
        var c = candidate.physics();
        var r = reference.physics();
        if (!same(c.units().viscosityToPhysical(c.viscosity()), r.units().viscosityToPhysical(r.viscosity())))
            return "physical viscosity";
        if (!same(c.units().densityToPhysical(c.densityReference()), r.units().densityToPhysical(r.densityReference())))
            return "physical reference density";
        if (!vector(
                c.initialVelocity(),
                c.units().velocityScale(),
                r.initialVelocity(),
                r.units().velocityScale())) return "physical initial velocity";
        if (!vector(
                c.acceleration(),
                c.units().accelerationScale(),
                r.acceleration(),
                r.units().accelerationScale())) return "physical acceleration";
        var cg = candidate.config().grid();
        var rg = reference.config().grid();
        if (!same(cg.nx() * c.voxelWidth(), rg.nx() * r.voxelWidth())
                || !same(cg.ny() * c.voxelWidth(), rg.ny() * r.voxelWidth())
                || !same(cg.nz() * c.voxelWidth(), rg.nz() * r.voxelWidth())) return "physical domain extent";
        if (c.shear() != null || r.shear() != null) {
            if (c.shear() == null
                    || r.shear() == null
                    || !c.shear().modeY().equals(r.shear().modeY())
                    || !c.shear().modeZ().equals(r.shear().modeZ())
                    || !same(
                            c.units().velocityToPhysical(c.shear().amplitude()),
                            r.units().velocityToPhysical(r.shear().amplitude()))
                    || c.voxelWidth() != r.voxelWidth()) return "shear amplitude/modes or physical phase origin";
        }
        var a = candidate.config().geometry();
        var b = reference.config().geometry();
        if (!a.faces().equals(b.faces()) || !java.util.Objects.equals(a.openBoundary(), b.openBoundary()))
            return "open/periodic/wall boundary inputs";
        if (!a.boxes().equals(b.boxes())
                || !a.spheres().equals(b.spheres())
                || !a.cylinders().equals(b.cylinders())) return "primitive geometry";
        if (candidate.meshes().size() != reference.meshes().size()) return "mesh count";
        for (int i = 0; i < candidate.meshes().size(); i++) {
            var cm = candidate.meshes().get(i);
            var rm = reference.meshes().get(i);
            if (!cm.sha256().equals(rm.sha256())
                    || cm.mesh().id() != rm.mesh().id()
                    || !cm.min().equals(rm.min())
                    || !cm.max().equals(rm.max())
                    || !cm.mesh().rotationDegrees().equals(rm.mesh().rotationDegrees()))
                return "resolved mesh geometry";
        }
        if (!java.util.Objects.equals(
                candidate.config().physics().forceReference(),
                reference.config().physics().forceReference())) return "drag reference definitions";
        return null;
    }

    private static boolean vector(Vector3 a, double aScale, Vector3 b, double bScale) {
        return same(a.x() * aScale, b.x() * bScale)
                && same(a.y() * aScale, b.y() * bScale)
                && same(a.z() * aScale, b.z() * bScale);
    }

    private static boolean same(double a, double b) {
        return Snapshot.sameTime(a, b);
    }
}
