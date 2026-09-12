# OBJ fixtures

`mesh_demo.obj` is the built-in textured torus (288 triangles).

`blender_coordinate_fixture.obj` was exported by Blender 5.2.0 with
`bpy.ops.wm.obj_export(forward_axis='NEGATIVE_Z', up_axis='Y', export_uv=True,
export_normals=True, export_materials=False, export_triangulated_mesh=True)`.
Its Blender positions are `(0.25,0.5,1)`, `(2.25,0.5,1)`, `(0.25,3.5,1)`,
`(0.25,0.5,5)`; faces are `(0,2,1)`, `(0,1,3)`, `(0,3,2)`, `(1,2,3)`.
Each face uses UVs `(0.125,0.25)`, `(0.875,0.25)`, `(0.125,0.75)`.
The off-origin tetrahedron verifies the editor's `(x,y,z) -> (x,z,-y)` basis
change, handedness, unequal extents, face winding and one-time UV V conversion.
