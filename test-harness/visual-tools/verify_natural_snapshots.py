#!/usr/bin/env python3
"""Verify a before/peak/after capture from an unmodified natural world."""
import argparse
from collections import defaultdict
import hashlib
import json
import re
import sys
from pathlib import Path


def fail(message):
    failures.append(message)


def material(block):
    return block.upper().split(":")[-1]


def block_states(snapshot, path):
    result = {}
    bounds = snapshot.get("bounds")
    required = ("minX", "maxX", "minY", "maxY", "minZ", "maxZ")
    if not isinstance(bounds, dict) or any(key not in bounds for key in required):
        fail(f"{path.name}: missing or incomplete bounds object")
        return result
    if any(bounds[lo] > bounds[hi] for lo, hi in (("minX", "maxX"), ("minY", "maxY"), ("minZ", "maxZ"))):
        fail(f"{path.name}: invalid bounds {bounds}")
    for block in snapshot.get("blocks", []):
        pos = (block["x"], block["y"], block["z"])
        x, y, z = pos
        if not (bounds["minX"] <= x <= bounds["maxX"] and bounds["minY"] <= y <= bounds["maxY"]
                and bounds["minZ"] <= z <= bounds["maxZ"]):
            fail(f"{path.name}: block outside bounds at {pos}")
        if pos in result:
            fail(f"{path.name}: duplicate block coordinate {pos}")
        result[pos] = (block["material"], block["data"])
    return result


def counts(snapshot):
    snow_blocks = ice_blocks = snow_layers = 0
    for block in snapshot["blocks"]:
        kind = material(block["material"])
        if kind == "SNOW":
            snow_blocks += 1
            match = re.search(r"(?:^|,|\[)layers=(\d+)", block["data"])
            if not match:
                fail(f"{snapshot.get('stage', '?')}: snow at {(block['x'], block['y'], block['z'])} lacks layers data")
            else:
                snow_layers += int(match.group(1))
        elif kind == "ICE":
            ice_blocks += 1
    return {"snowBlocks": snow_blocks, "iceBlocks": ice_blocks, "snowLayers": snow_layers}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("snapshots", type=Path, help="directory containing the three JSON snapshots")
    parser.add_argument("--output", type=Path, required=True, help="verification report JSON path")
    parser.add_argument("--require-stacks", action="store_true", help="Require canopy snow and supported multi-block stacks")
    parser.add_argument("--require-details", action="store_true", help="Require covered plants, varied depth and softer protected border")
    args = parser.parse_args()

    global failures
    failures = []
    output_path = args.output.resolve()
    files = sorted(path for path in args.snapshots.glob("*.json")
                   if path.name != "result.json" and path.resolve() != output_path)
    if len(files) != 3:
        fail(f"expected exactly three snapshot JSON files (before, peak, after), found {len(files)}")
    snapshots = []
    for path in files:
        try:
            snapshots.append(json.loads(path.read_text(encoding="utf-8")))
        except (OSError, json.JSONDecodeError) as error:
            fail(f"could not read {path}: {error}")
    if len(snapshots) != 3:
        report = {"passed": False, "failures": failures, "stages": []}
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(report, indent=2))
        return 1

    expected_stages = ["before", "peak", "after"]
    records = []
    states = []
    measured = []
    for index, (path, snapshot, stage) in enumerate(zip(files, snapshots, expected_stages)):
        if snapshot.get("stage") != stage:
            fail(f"{path.name}: expected stage {stage!r}, got {snapshot.get('stage')!r}")
        states.append(block_states(snapshot, path))
        count = counts(snapshot)
        measured.append(count)
        summary = snapshot.get("summary")
        if not isinstance(summary, dict):
            fail(f"{path.name}: missing summary object")
            summary = {}
        for key, field in (("snowBlocks", "snowBlocks"), ("iceBlocks", "iceBlocks"), ("snowLayers", "snowLayers")):
            value = summary.get(key)
            if not isinstance(value, int) or value != count[field]:
                fail(f"{path.name}: summary.{key}={value!r}, measured {count[field]}")
        if not isinstance(summary.get("ownedBlocks"), int) or summary["ownedBlocks"] < 0:
            fail(f"{path.name}: summary.ownedBlocks must be a non-negative integer")
        if index == 0:
            reference_bounds = snapshot.get("bounds")
            reference_seed = snapshot.get("seed")
            reference_camera = snapshot.get("camera")
            if reference_seed not in (20261024, "20261024"):
                fail(f"{path.name}: expected natural-world seed 20261024, got {reference_seed!r}")
            reference_generator = snapshot.get("generator")
            if reference_generator != "minecraft:normal":
                fail(f"{path.name}: expected generator minecraft:normal, got {reference_generator!r}")
            if not isinstance(reference_camera, dict) or any(key not in reference_camera for key in ("x", "y", "z", "yaw", "pitch")):
                fail(f"{path.name}: missing or incomplete camera object")
        else:
            if snapshot.get("bounds") != reference_bounds:
                fail(f"{path.name}: bounds differ from baseline")
            if snapshot.get("seed") != reference_seed:
                fail(f"{path.name}: seed differs from baseline")
            if snapshot.get("generator") != reference_generator:
                fail(f"{path.name}: generator differs from baseline")
            if snapshot.get("camera") != reference_camera:
                fail(f"{path.name}: camera differs from baseline")
        records.append({"snapshot": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
                        "stage": stage, "seed": snapshot.get("seed"), "generator": snapshot.get("generator"),
                        "bounds": snapshot.get("bounds"),
                        "camera": snapshot.get("camera"), "summary": summary, "measured": count})

    if states[0] != states[2]:
        fail("after block states differ from before (coordinate/material/data mismatch)")

    before, peak = states[0], states[1]
    added_snow = [pos for pos, state in peak.items() if material(state[0]) == "SNOW"
                  and state != before.get(pos) and material(before.get(pos, ("AIR", ""))[0]) in ("AIR", "SHORT_GRASS", "FERN")]
    added_ice = [pos for pos, state in peak.items() if material(state[0]) == "ICE"
                 and state != before.get(pos) and material(before.get(pos, ("AIR", ""))[0]) == "WATER"]
    covered_plants = sum(material(before.get(pos, ("AIR", ""))[0]) in ("SHORT_GRASS", "FERN") for pos in added_snow)
    if not added_snow:
        fail("peak has no newly added snow over baseline air")
    if not added_ice:
        fail("peak has no newly added ice over baseline water")
    for pos, state in peak.items():
        kind = material(state[0])
        original = before.get(pos)
        baseline_kind = material(original[0]) if original else "AIR"
        unchanged = state == original
        if kind == "ICE" and baseline_kind != "WATER" and not unchanged:
            fail(f"peak ice at {pos} replaces baseline {baseline_kind}, expected water")
        if kind == "SNOW" and baseline_kind not in ("AIR", "SHORT_GRASS", "FERN") and not unchanged:
            fail(f"peak snow at {pos} replaces baseline {baseline_kind}, expected air or a supported small plant")

    for pos in before.keys() | peak.keys():
        old = before.get(pos, ("AIR", "minecraft:air"))
        new = peak.get(pos, ("AIR", "minecraft:air"))
        if old != new and material(new[0]) not in ("SNOW", "ICE"):
            fail(f"unexpected non-winter terrain change at {pos}: {old} -> {new}")
    if snapshots[2].get("summary", {}).get("ownedBlocks") != 0:
        fail("after phase still owns winter blocks")

    for field in ("snowBlocks", "iceBlocks", "snowLayers"):
        if measured[1][field] <= measured[0][field]:
            fail(f"peak {field} did not increase over before")
    columns = defaultdict(list)
    for pos in added_snow:
        match = re.search(r"layers=(\d+)", peak[pos][1])
        if match: columns[(pos[0], pos[2])].append((pos[1], int(match.group(1))))
    canopy_columns = stacked_columns = maximum_depth = 0
    edge_depths, interior_depths, unique_depths = [], [], set()
    for (x, z), rows in columns.items():
        rows.sort()
        depth = sum(layers for _, layers in rows)
        maximum_depth = max(maximum_depth, depth)
        unique_depths.add(depth)
        border_distance=min(x-reference_bounds['minX'],reference_bounds['maxX']-x,z-reference_bounds['minZ'],reference_bounds['maxZ']-z)
        (edge_depths if border_distance<4 else interior_depths).append(depth)
        if depth > 8: stacked_columns += 1
        base = before.get((x, rows[0][0]-1, z), ("AIR", ""))[0]
        if material(base).endswith("_LEAVES"): canopy_columns += 1
        if args.require_stacks:
            for index, (y, layers) in enumerate(rows[:-1]):
                if layers != 8 or rows[index+1][0] != y+1:
                    fail(f"unsupported/gapped winter snow stack at {x},{y},{z}")
    if args.require_stacks and (canopy_columns == 0 or stacked_columns == 0 or maximum_depth <= 8):
        fail("required canopy cover and multi-block snow stacks were not demonstrated")
    edge_mean=sum(edge_depths)/len(edge_depths) if edge_depths else 0
    interior_mean=sum(interior_depths)/len(interior_depths) if interior_depths else 0
    if args.require_details and (covered_plants==0 or len(unique_depths)<4 or not 0<edge_mean<interior_mean):
        fail("required covered plants, varied depth and reduced border depth not demonstrated")
    report = {"passed": not failures, "failures": failures, "allAfterBlockStatesRestored": states[0] == states[2],
              "peakDepths": sorted(unique_depths), "meanBorderDepth": edge_mean, "meanInteriorDepth": interior_mean, "peakCoveredSmallPlants": covered_plants, "peakCanopySnowColumns": canopy_columns, "peakStackedColumns": stacked_columns,
              "peakMaximumColumnLayers": maximum_depth, "addedSnowBlocksAtPeak": len(added_snow), "addedIceBlocksAtPeak": len(added_ice), "stages": records}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    return int(bool(failures))


if __name__ == "__main__":
    sys.exit(main())
