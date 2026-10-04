#!/usr/bin/env python3
"""Generate the Sproutwatch pen prefab (plain Hytale prefab JSON, version 8).

Layout (anchor = min corner of the floor at 0,0,0):
  floor      y=0, Soil_Grass, covering the fence ring and the chair tile
  fence ring y=1..FENCE_HEIGHT around a WIDTH x DEPTH interior (default 16 x 12, 4:3 landscape;
             Hytale fences are one block tall, so FENCE_HEIGHT = 1)
  interior   y=1..CLEAR_HEIGHT set to Empty so paste flattens/clears terrain
  chair      one tile outside the -z long side, centred, facing +z (into the pen)

Rotation convention (verified against vanilla Village prefabs, chairs face tables):
  0 -> +z   1 -> +x   2 -> -z   3 -> -x

Fence orientation (from vanilla Server/Prefabs/Npc/Kweebec/Autumn/Bunny_Area/
Kweebec_Autumn_Bunny_Area_001.prefab.json; a ring with one shared rotation leaves gaps):
  straight run along x (neighbours west/east)   -> Wood_Hardwood_Fence, rotation 0
  straight run along z (neighbours north/south) -> Wood_Hardwood_Fence, rotation 1
  corners -> *Wood_Hardwood_Fence_State_Definitions_Corner (leading asterisk as vanilla),
             rotation by the sides joined: east+south 1, west+south 0, east+north 2, west+north 3
  (north = -z, south = +z, east = +x, west = -x)

Edit the constants, run the script, redeploy.
"""
import json, pathlib

WIDTH, DEPTH = 16, 12          # interior footprint, 4:3 landscape (wide from the chair)
FENCE_HEIGHT = 1               # fence rows at y=1..FENCE_HEIGHT (Hytale fences are one block tall)
CLEAR_HEIGHT = 4               # interior air cleared up to this y
FLOOR = "Soil_Grass"
FENCE = "Wood_Hardwood_Fence"
CHAIR = "Furniture_Village_Chair"
FENCE_CORNER = "*Wood_Hardwood_Fence_State_Definitions_Corner"   # leading asterisk exactly as vanilla writes it
CHAIR_X = WIDTH // 2           # x=8 for a 16-wide pen (interior spans x=1..16)
OUT = pathlib.Path(__file__).resolve().parents[1] / "src/main/resources/Server/Prefabs/Sproutwatch/sproutwatch_pen.prefab.json"

blocks = []
def fence_piece(x, z):
    """Block name + rotation for a ring tile, following the vanilla Kweebec Bunny_Area fence rule."""
    west_edge, east_edge = x == 0, x == WIDTH + 1
    north_edge, south_edge = z == 0, z == DEPTH + 1
    if (west_edge or east_edge) and (north_edge or south_edge):
        # corner: rotation by the two sides it joins (north=-z, south=+z, east=+x, west=-x)
        if west_edge and north_edge:  return FENCE_CORNER, 1   # joins east + south
        if east_edge and north_edge:  return FENCE_CORNER, 0   # joins west + south
        if west_edge and south_edge:  return FENCE_CORNER, 2   # joins east + north
        return FENCE_CORNER, 3                                 # joins west + north
    # Straight runs: vanilla mirrors the rotation by which side the interior is on
    # (north row 2 / south row 0, west column 3 / east column 1), so the rails face outward.
    if north_edge:
        return FENCE, 2    # run along x, interior toward +z
    if south_edge:
        return FENCE, 0    # run along x, interior toward -z
    if west_edge:
        return FENCE, 3    # run along z, interior toward +x
    return FENCE, 1        # run along z, interior toward -x

def put(x, y, z, name, **extra):
    b = {"x": x, "y": y, "z": z, "name": name}
    b.update(extra)
    blocks.append(b)

max_x, max_z = WIDTH + 1, DEPTH + 1      # fence ring occupies x=0|max_x, z=0|max_z
for x in range(0, max_x + 1):
    for z in range(0, max_z + 1):
        put(x, 0, z, FLOOR)
        on_ring = x in (0, max_x) or z in (0, max_z)
        if on_ring:
            name, rot = fence_piece(x, z)
            for y in range(1, FENCE_HEIGHT + 1):
                put(x, y, z, name, rotation=rot)
        else:
            for y in range(1, CLEAR_HEIGHT + 1):
                put(x, y, z, "Empty")

# chair tile just outside the -z long side, centred, facing +z into the pen
put(CHAIR_X, 0, -1, FLOOR)
put(CHAIR_X, 1, -1, CHAIR, rotation=0)
for y in range(2, CLEAR_HEIGHT + 1):
    put(CHAIR_X, y, -1, "Empty")

prefab = {
    "version": 8,
    "blockIdVersion": 11,
    "anchorX": 0, "anchorY": 0, "anchorZ": 0,
    "blocks": blocks,
    "entities": [],
}
OUT.parent.mkdir(parents=True, exist_ok=True)
OUT.write_text(json.dumps(prefab, indent=2) + "\n")
print(f"wrote {OUT.relative_to(OUT.parents[5])}: {len(blocks)} blocks, "
      f"interior {WIDTH}x{DEPTH}, box x0..{max_x} z-1..{max_z} y0..{CLEAR_HEIGHT}, chair at ({CHAIR_X},1,-1)")
