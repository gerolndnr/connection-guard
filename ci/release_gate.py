#!/usr/bin/env python3
"""Fail closed before publication unless this exact artifact has clean runtime evidence."""
import hashlib
import json
from pathlib import Path
import re
import subprocess

from startup_smoke import RUNTIMES, FAILURES
from verify_artifact import verify


class QualificationError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise QualificationError(message)


def evidence_file(root, relative, maximum):
    require(isinstance(relative, str), "Evidence path must be a relative path.")
    path = root / relative
    require(not Path(relative).is_absolute() and ".." not in Path(relative).parts,
            "Evidence must stay inside the operations workspace.")
    require(not path.is_symlink() and path.resolve().is_relative_to(root.resolve())
            and path.is_file() and path.stat().st_size <= maximum,
            "Evidence must be a bounded regular file inside the operations workspace.")
    return path


def validate_runtime_evidence(root, manifest):
    proofs = manifest.get("startup_evidence")
    require(isinstance(proofs, dict) and set(proofs) == {"paper", "velocity"},
            "Exact-JAR Paper and Velocity startup evidence is required before publication.")
    for platform, relative in proofs.items():
        path = evidence_file(root, relative, 16000)
        data = json.loads(path.read_text())
        require(data.get("schema") == 1 and data.get("kind") == "connection-guard-startup",
                "Unknown runtime qualification schema.")
        for key, expected in (("platform", platform), ("version", manifest["version"]),
                              ("artifact_sha256", manifest["sha256"]), ("source_commit", manifest["source_commit"]),
                              ("runtime", RUNTIMES[platform]["name"]), ("runtime_sha256", RUNTIMES[platform]["sha256"]),
                              ("other_plugins", []), ("telemetry_enabled", False)):
            require(data.get(key) == expected, "Runtime qualification mismatch: " + key)
        for key in ("plugin_enabled", "help_passed", "reload_passed", "shutdown_passed"):
            require(data.get(key) is True, "Runtime qualification did not pass: " + key)
        log = evidence_file(root, str(path.parent.relative_to(root) / "console.log"), 8000000)
        content = log.read_bytes()
        require(hashlib.sha256(content).hexdigest() == data.get("console_sha256"),
                "Runtime log differs from its recorded qualification hash.")
        text = content.decode("utf-8")
        require(all(marker in text for marker in ("Done (", "Overview of commands", "Config has been reloaded!")),
                "Runtime log does not show plugin startup, command execution and reload.")
        require(not any(marker in text for marker in FAILURES), "Runtime log contains a plugin/runtime error.")
    return sorted(proofs)


def qualify(root, checkout, artifact, manifest):
    require(re.fullmatch(r"[a-f0-9]{40}", manifest.get("source_commit", "")), "Invalid qualified source commit.")
    require(re.fullmatch(r"[a-f0-9]{64}", manifest.get("sha256", "")), "Invalid qualified artifact hash.")
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=checkout, text=True).strip()
    require(head == manifest["source_commit"], "Checkout differs from the qualified source commit.")
    require(not subprocess.check_output(["git", "status", "--porcelain"], cwd=checkout, text=True).strip(),
            "Release source checkout contains uncommitted changes.")
    require(hashlib.sha256(artifact.read_bytes()).hexdigest() == manifest["sha256"],
            "Artifact differs from the qualified release JAR.")
    packaging = verify(artifact, manifest["version"])
    platforms = validate_runtime_evidence(root, manifest)
    return {"qualified": True, "version": manifest["version"], "sha256": packaging["sha256"],
            "source_commit": head, "startup_platforms": platforms,
            "all_platform_versions_tested": False}
