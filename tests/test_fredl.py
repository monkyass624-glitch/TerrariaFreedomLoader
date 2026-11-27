#!/usr/bin/env python3
import json
import pathlib
import subprocess
import sys
import tempfile
import zipfile

CLI = pathlib.Path(sys.argv[1]).resolve()
GAME = "1.4.5.6.4"
PLATFORM = "android-arm64"

def manifest(**overrides):
    value = {
        "format": "FREDL",
        "formatVersion": 1,
        "id": "com.example.firstmod",
        "name": "First Mod",
        "version": "1.0.0",
        "game": {"id": "terraria-mobile", "versions": [GAME]},
        "platforms": [PLATFORM],
        "entrypoint": "mod.dll",
        "assetsDirectory": "assets",
        "dependencies": {},
    }
    value.update(overrides)
    return value

def make_package(path, data=None, entries=None):
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        raw = json.dumps(data if data is not None else manifest()).encode("utf-8")
        zf.writestr("manifest.json", raw)
        zf.writestr("mod.dll", b"managed assembly test placeholder")
        zf.writestr("assets/readme.txt", b"hello from FREDL")
        for name, body in (entries or []):
            zf.writestr(name, body)

def run(*args, expected=0):
    result = subprocess.run([str(CLI), *map(str, args)], text=True, capture_output=True)
    if result.returncode != expected:
        raise AssertionError(
            f"command {args} returned {result.returncode}, expected {expected}\n"
            f"stdout={result.stdout}\nstderr={result.stderr}"
        )
    return result.stdout.strip()

with tempfile.TemporaryDirectory(prefix="fredl-tests-") as temporary:
    root = pathlib.Path(temporary)
    good = root / "good.fredl"
    make_package(good)
    assert run("validate", good, GAME, PLATFORM) == "VALID"

    # Wrong game version and platform are both rejected.
    assert "game version" in run("validate", good, "9.9.9", PLATFORM, expected=2)
    bad_platform = root / "bad-platform.fredl"
    make_package(bad_platform, manifest(platforms=["windows-x64"]))
    assert "platform" in run("validate", bad_platform, GAME, PLATFORM, expected=2)

    # Unknown format version and malformed JSON must be rejected.
    bad_version = root / "bad-version.fredl"
    make_package(bad_version, manifest(formatVersion=2))
    assert "formatVersion" in run("validate", bad_version, GAME, PLATFORM, expected=2)
    malformed = root / "malformed.fredl"
    with zipfile.ZipFile(malformed, "w", compression=zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("manifest.json", b'{"format": ')
    assert "JSON" in run("validate", malformed, GAME, PLATFORM, expected=2)

    # Archive traversal is rejected before extraction.
    traversal = root / "traversal.fredl"
    make_package(traversal, entries=[("../outside.txt", b"bad")])
    assert "unsafe archive path" in run("validate", traversal, GAME, PLATFORM, expected=2)

    # Installer extracts to the manifest ID directory and never writes outside it.
    mods = root / "mods"
    assert run("install", good, mods, GAME, PLATFORM) == "INSTALLED"
    installed = mods / "com.example.firstmod"
    assert (installed / "manifest.json").is_file()
    assert (installed / "mod.dll").read_bytes() == b"managed assembly test placeholder"
    assert (installed / "assets" / "readme.txt").read_text() == "hello from FREDL"
    assert not (root / "outside.txt").exists()


    # Dependency resolver creates a topological startup order and blocks unsafe selections.
    base_pkg = root / "base.fredl"
    plugin_pkg = root / "plugin.fredl"
    make_package(base_pkg, manifest(
        id="com.example.base", name="Base Library", version="1.2.0"))
    make_package(plugin_pkg, manifest(
        id="com.example.plugin", name="Dependent Plugin", version="2.0.0",
        dependencies={"com.example.base": ">=1.0.0"}))
    planned_mods = root / "planned-mods"
    assert run("install", base_pkg, planned_mods, GAME, PLATFORM) == "INSTALLED"
    assert run("install", plugin_pkg, planned_mods, GAME, PLATFORM) == "INSTALLED"

    # A dependent mod cannot be selected until its dependency is selected.
    assert "not selected" in run(
        "select", planned_mods, "com.example.plugin", "on", GAME, PLATFORM, expected=6)
    assert run("select", planned_mods, "com.example.base", "on", GAME, PLATFORM) == "SELECTED"
    assert run("select", planned_mods, "com.example.plugin", "on", GAME, PLATFORM) == "SELECTED"

    # The load order must place dependencies before dependents.
    plan = json.loads(run("list", planned_mods, GAME, PLATFORM))
    assert plan["loadOrderReady"] is True
    assert plan["loadOrder"] == ["com.example.base", "com.example.plugin"]

    # A dependency cannot be deselected while an enabled dependent needs it.
    assert "not selected" in run(
        "select", planned_mods, "com.example.base", "off", GAME, PLATFORM, expected=6)
    assert run("select", planned_mods, "com.example.plugin", "off", GAME, PLATFORM) == "DESELECTED"
    assert run("select", planned_mods, "com.example.base", "off", GAME, PLATFORM) == "DESELECTED"


    # Version constraints are enforced rather than merely checking dependency presence.
    incompatible_pkg = root / "incompatible.fredl"
    make_package(incompatible_pkg, manifest(
        id="com.example.incompatible", name="Incompatible", version="1.0.0",
        dependencies={"com.example.base": ">=3.0.0"}))
    assert run("install", incompatible_pkg, planned_mods, GAME, PLATFORM) == "INSTALLED"
    assert "version mismatch" in run(
        "select", planned_mods, "com.example.incompatible", "on", GAME, PLATFORM, expected=6)

    # A preselected cyclic dependency graph is rejected by the load-order planner.
    cycle_a = root / "cycle-a.fredl"
    cycle_b = root / "cycle-b.fredl"
    make_package(cycle_a, manifest(
        id="com.example.cyclea", name="Cycle A", version="1.0.0",
        dependencies={"com.example.cycleb": "*"}))
    make_package(cycle_b, manifest(
        id="com.example.cycleb", name="Cycle B", version="1.0.0",
        dependencies={"com.example.cyclea": "*"}))
    cycle_mods = root / "cycle-mods"
    assert run("install", cycle_a, cycle_mods, GAME, PLATFORM) == "INSTALLED"
    assert run("install", cycle_b, cycle_mods, GAME, PLATFORM) == "INSTALLED"
    (cycle_mods / "com.example.cyclea" / ".fredl-selected").write_text("FREDL_SELECTED_V1\n")
    (cycle_mods / "com.example.cycleb" / ".fredl-selected").write_text("FREDL_SELECTED_V1\n")
    cycle_report = json.loads(run("list", cycle_mods, GAME, PLATFORM))
    assert cycle_report["loadOrderReady"] is False
    assert "cycle" in cycle_report["loadOrderError"]

    # Directory scanner reports installed package files; installed folders are not .fredl files.
    packages = root / "packages"
    packages.mkdir()
    make_package(packages / "one.fredl")
    (packages / "not-a-mod.zip").write_bytes(b"not a package")
    report = json.loads(run("scan", packages, GAME, PLATFORM))
    assert len(report) == 1 and report[0]["valid"] is True
    assert report[0]["id"] == "com.example.firstmod"

print("FREDL tests: PASS")
