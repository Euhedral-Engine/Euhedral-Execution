"""Independent VTK reader check for the non-cubic Java writer fixture."""
import math
import sys
from vtkmodules.vtkIOXML import vtkXMLImageDataReader
from vtkmodules.vtkCommonCore import vtkVersion

reader = vtkXMLImageDataReader()
errors = []
reader.AddObserver("ErrorEvent", lambda *_: errors.append("reader error"))
reader.SetFileName(sys.argv[1])
reader.Update()
assert not errors, errors
image = reader.GetOutput()
assert image.GetExtent() == (0, 3, 0, 4, 0, 5)
assert image.GetNumberOfCells() == 60
assert image.GetOrigin() == (0.0, 0.0, 0.0)
assert image.GetSpacing() == (0.02, 0.02, 0.02)
assert image.GetPointData().GetNumberOfArrays() == 0
assert image.GetFieldData().GetArray("time_seconds").GetTuple1(0) == 0
arrays = image.GetCellData()
assert arrays.GetArray("velocity").GetNumberOfComponents() == 3
for z in range(5):
    for y in range(4):
        for x in range(3):
            index = x + 3 * (y + 4 * z)
            key = x + 10 * y + 100 * z
            solid = (x, y, z) == (1, 2, 3)
            expected = {
                "velocity": (0, 0, 0) if solid else ((.002 + .00001 * key) * 5, (-.003 + .000002 * key) * 5, (.004 - .000003 * key) * 5),
                "density": (0 if solid else (2 + .0001 * key) * 500,),
                "gauge_pressure": (0 if solid else .0001 * key / 3 * 12500,),
                "solid": (int(solid),),
                "obstacle_id": (9 if solid else 0,),
            }
            bounds = image.GetCell(index).GetBounds()
            for axis, coordinate in enumerate((x, y, z)):
                assert math.isclose((bounds[2 * axis] + bounds[2 * axis + 1]) / 2, (coordinate + .5) * .02, abs_tol=1e-14)
            for name, values in expected.items():
                actual = arrays.GetArray(name).GetTuple(index)
                assert all(math.isclose(a, b, abs_tol=1e-10, rel_tol=1e-12) for a, b in zip(actual, values)), (index, name, actual, values)
print("VTK", vtkVersion.GetVTKVersion(), "verified", sys.argv[1])
