#!/usr/bin/env python3
"""Verify packaging and bytecode of the combined plugin; does not start a server."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct
import sys
import zipfile


ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.github.gerolndnr.connectionguard"
ENTRYPOINTS = {
    "spigot": PACKAGE + ".spigot.ConnectionGuardSpigotPlugin",
    "bungee": PACKAGE + ".bungee.ConnectionGuardBungeePlugin",
    "velocity": PACKAGE + ".velocity.ConnectionGuardVelocityPlugin",
}
MAJORS = {"core": 52, "spigot": 52, "bungee": 52, "velocity": 61}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def yaml_scalar(text, field):
    match = re.search(r"^" + re.escape(field) + r":\s*([^\r\n]+)$", text, re.MULTILINE)
    require(match is not None, f"Missing YAML field: {field}")
    return match.group(1).strip().strip("'\"")


def verify(artifact, version):
    with zipfile.ZipFile(artifact) as jar:
        names = jar.namelist()
        require(jar.testzip() is None, "The JAR contains a corrupt ZIP entry.")
        for name in ("plugin.yml", "bungee.yml", "velocity-plugin.json", "config.yml", "translation/en.yml"):
            require(name in names and jar.read(name).strip(), f"Missing or empty resource: {name}")
            require(names.count(name) == 1, f"Duplicate plugin resource: {name}")
        for platform, main in ENTRYPOINTS.items():
            require(main.replace(".", "/") + ".class" in names, f"Missing {platform} entrypoint: {main}")
        for descriptor, platform in (("plugin.yml", "spigot"), ("bungee.yml", "bungee")):
            text = jar.read(descriptor).decode("utf-8")
            require(yaml_scalar(text, "version") == version, f"Wrong or unexpanded version in {descriptor}")
            require(yaml_scalar(text, "main") == ENTRYPOINTS[platform], f"Wrong entrypoint in {descriptor}")
        velocity = json.loads(jar.read("velocity-plugin.json"))
        require(velocity.get("id") == "connection-guard", "Wrong Velocity plugin ID.")
        require(velocity.get("main") == ENTRYPOINTS["velocity"], "Wrong Velocity entrypoint.")
        require(velocity.get("version") == version, "Velocity version differs from the Gradle project version.")

        counts = {}
        for module, expected_major in MAJORS.items():
            prefix = PACKAGE.replace(".", "/") + "/" + module + "/"
            classes = [name for name in names if name.startswith(prefix) and name.endswith(".class")]
            require(classes, f"No classes found for module: {module}")
            for name in classes:
                header = jar.read(name)[:8]
                require(len(header) == 8 and header[:4] == b"\xca\xfe\xba\xbe", f"Invalid class file: {name}")
                minor, major = struct.unpack(">HH", header[4:8])
                require(major == expected_major and minor == 0,
                        f"Unexpected bytecode in {name}: {major}.{minor}, expected {expected_major}.0")
            counts[module] = {"classes": len(classes), "class_major": expected_major}
        for prefix in ("org/bukkit/", "net/md_5/bungee/", "com/velocitypowered/api/"):
            require(not any(name.startswith(prefix) and name.endswith(".class") for name in names),
                    f"Server-provided API was bundled into the plugin: {prefix}")
        library_prefix = PACKAGE.replace(".", "/") + "/libs/"
        for notice in ("Apache-2.0.txt", "THIRD-PARTY-NOTICES.txt"):
            require("META-INF/connection-guard/" + notice in names, f"Missing HTTP dependency notice: {notice}")
        for dependency in ("okhttp3/OkHttpClient.class", "okio/Buffer.class", "kotlin/jvm/internal/Intrinsics.class"):
            require(library_prefix + dependency in names, f"Missing bundled HTTP dependency: {dependency}")
        for prefix in ("okhttp3/", "okio/", "kotlin/"):
            require(not any(name.startswith(prefix) and name.endswith(".class") for name in names),
                    f"HTTP dependency was not relocated: {prefix}")
    return {"artifact": str(artifact), "version": version, "sha256": hashlib.sha256(artifact.read_bytes()).hexdigest(),
            "modules": counts, "packaging_verified": True, "server_runtime_tested": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifact", type=Path, nargs="?")
    arguments = parser.parse_args()
    match = re.search(r'^version\s*=\s*"([^"\n]+)"', (ROOT / "build.gradle.kts").read_text(), re.MULTILINE)
    require(match is not None, "Cannot determine Gradle project version.")
    artifact = arguments.artifact
    if artifact is None:
        artifacts = list((ROOT / "build" / "libs").glob("*-all.jar"))
        require(len(artifacts) == 1, f"Expected exactly one combined plugin JAR, found {len(artifacts)}.")
        artifact = artifacts[0]
    print(json.dumps(verify(artifact, match.group(1)), indent=2))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, zipfile.BadZipFile, KeyError, UnicodeError) as error:
        print(f"Artifact verification failed: {error}", file=sys.stderr)
        sys.exit(1)
