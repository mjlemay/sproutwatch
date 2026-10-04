#!/usr/bin/env python3
"""Generate smaller copies of the Kweebec Sapling hats for Sprout_Sproutling.

Attachments have no scale field (ModelAttachment = Model, Texture, GradientId, GradientSet,
Weight), so the mod ships scaled copies of the hat models instead.

Why 85%: the Sproutling head is 28 x 23.5 x 28 vs the Sapling's 30 x 28 x 28 (head box size x
stretch, both pivoted at the bottom of the head). Sapling hats sit on a head top 28 px above the
pivot; the Sproutling's is ~23.75 px up, so unscaled hats float ~4 px high and look large.
Scaling every child position and shape offset around the root (the Head bone anchor) by SCALE
shrinks the hat and lowers it onto the shorter head together. Sizes scale through "stretch",
which in the blockymodel format resizes a shape without changing its UVs.

Reads the vanilla hats from the release Assets.zip, writes
src/main/resources/Common/NPC/Sproutwatch/Hats/<name>.blockymodel, and points the Hair options
in Server/Models/Sproutwatch/Sprout_Sproutling.json at them (textures stay vanilla).
Change SCALE, run the script, redeploy.
"""
import json, pathlib, zipfile

SCALE = 0.85

ASSETS = pathlib.Path.home() / "Library/Application Support/Hytale/install/release/package/game/latest/Assets.zip"
VANILLA_DIR = "NPC/Intelligent/Kweebec_Sapling/Models/Attachments/Cosmetics/Head/Haircuts"
MOD_DIR = "NPC/Sproutwatch/Hats"
ROOT = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources"
MODEL_JSON = ROOT / "Server/Models/Sproutwatch/Sprout_Sproutling.json"


def scale_vec(v, f):
    return {k: (v[k] * f if k in ("x", "y", "z") else v[k]) for k in v}


def scale_node(node, f, is_root):
    # The root's position is the bone anchor (where the hat attaches): keep it. Everything below it,
    # and every shape's offset and stretch, scales around that anchor.
    if not is_root and "position" in node:
        node["position"] = scale_vec(node["position"], f)
    shape = node.get("shape")
    if shape:
        if "offset" in shape:
            shape["offset"] = scale_vec(shape["offset"], f)
        shape["stretch"] = scale_vec(shape.get("stretch", {"x": 1, "y": 1, "z": 1}), f)
    for child in node.get("children", []):
        scale_node(child, f, False)


def main():
    model = json.loads(MODEL_JSON.read_text())
    hair = model["RandomAttachmentSets"]["Hair"]
    out_dir = ROOT / "Common" / MOD_DIR
    out_dir.mkdir(parents=True, exist_ok=True)
    written = set()
    with zipfile.ZipFile(ASSETS) as z:
        for name, option in hair.items():
            if name in ("null", "Bud"):
                continue
            base = pathlib.PurePosixPath(option["Model"]).name
            if base not in written:
                hat = json.loads(z.read(f"Common/{VANILLA_DIR}/{base}"))
                for root in hat["nodes"]:
                    scale_node(root, SCALE, True)
                (out_dir / base).write_text(json.dumps(hat, indent=2) + "\n")
                written.add(base)
            option["Model"] = f"{MOD_DIR}/{base}"
    MODEL_JSON.write_text(json.dumps(model, indent=2) + "\n")
    print(f"scaled {len(written)} hat model(s) to {SCALE:.0%}: {sorted(written)}")


if __name__ == "__main__":
    main()
