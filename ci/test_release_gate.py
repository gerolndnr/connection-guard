#!/usr/bin/env python3
"""Regression checks: missing runtime classes and stale/failed runtime proof must fail."""
import copy
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
import warnings
import zipfile

from release_gate import QualificationError, validate_runtime_evidence
from startup_smoke import RUNTIMES
from verify_artifact import verify, PACKAGE

ROOT = Path(__file__).resolve().parents[1]


class RuntimeEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.manifest = {"version": "0.4.11", "sha256": "a" * 64, "source_commit": "b" * 40, "startup_evidence": {}}
        for platform in RUNTIMES:
            directory = self.root / platform
            directory.mkdir()
            log = b"Done (1s)!\nOverview of commands\nConfig has been reloaded!\n"
            (directory / "console.log").write_bytes(log)
            result = {"schema": 1, "kind": "connection-guard-startup", "platform": platform,
                      "version": self.manifest["version"], "artifact_sha256": self.manifest["sha256"],
                      "source_commit": self.manifest["source_commit"], "runtime": RUNTIMES[platform]["name"],
                      "runtime_sha256": RUNTIMES[platform]["sha256"], "console_sha256": hashlib.sha256(log).hexdigest(),
                      "other_plugins": [], "telemetry_enabled": False, "plugin_enabled": True,
                      "help_passed": True, "reload_passed": True, "shutdown_passed": True}
            (directory / "result.json").write_text(json.dumps(result))
            self.manifest["startup_evidence"][platform] = platform + "/result.json"

    def tearDown(self):
        self.temp.cleanup()

    def test_matching_proof_passes(self):
        self.assertEqual(["paper", "velocity"], validate_runtime_evidence(self.root, self.manifest))

    def test_missing_platform_fails(self):
        del self.manifest["startup_evidence"]["paper"]
        with self.assertRaises(QualificationError):
            validate_runtime_evidence(self.root, self.manifest)

    def test_changed_artifact_or_source_fails(self):
        for key in ("sha256", "source_commit", "version"):
            manifest = copy.deepcopy(self.manifest)
            manifest[key] = "changed"
            with self.assertRaises(QualificationError):
                validate_runtime_evidence(self.root, manifest)

    def test_failed_runtime_and_wrong_runtime_fail(self):
        original = (self.root / "paper/result.json").read_text()
        for key, value in (("plugin_enabled", False), ("reload_passed", False), ("shutdown_passed", False),
                           ("runtime_sha256", "c" * 64), ("other_plugins", ["helper"]), ("schema", 2)):
            result = json.loads(original)
            result[key] = value
            (self.root / "paper/result.json").write_text(json.dumps(result))
            with self.assertRaises(QualificationError):
                validate_runtime_evidence(self.root, self.manifest)

    def test_tampered_log_fails(self):
        (self.root / "paper/console.log").write_text("different log")
        with self.assertRaises(QualificationError):
            validate_runtime_evidence(self.root, self.manifest)

    def test_error_in_hash_matching_log_fails(self):
        log = (self.root / "paper/console.log").read_bytes() + b"NoClassDefFoundError: org/bstats/MetricsBase\n"
        (self.root / "paper/console.log").write_bytes(log)
        result = json.loads((self.root / "paper/result.json").read_text())
        result["console_sha256"] = hashlib.sha256(log).hexdigest()
        (self.root / "paper/result.json").write_text(json.dumps(result))
        with self.assertRaises(QualificationError):
            validate_runtime_evidence(self.root, self.manifest)

    def test_path_escape_and_symlink_fail(self):
        for relative in ("../outside.json", "/tmp/outside.json"):
            self.manifest["startup_evidence"]["paper"] = relative
            with self.assertRaises(QualificationError):
                validate_runtime_evidence(self.root, self.manifest)
        (self.root / "alias.json").symlink_to(self.root / "paper/result.json")
        self.manifest["startup_evidence"]["paper"] = "alias.json"
        with self.assertRaises(QualificationError):
            validate_runtime_evidence(self.root, self.manifest)


class ArtifactRegressionTest(unittest.TestCase):
    def test_missing_or_duplicate_general_failover_settings_fail_before_runtime(self):
        artifact = next((ROOT / "build/libs").glob("*-all.jar"))
        with zipfile.ZipFile(artifact) as jar:
            version = json.loads(jar.read("velocity-plugin.json"))["version"]
            resources = {name: jar.read(name) for name in jar.namelist()}
        entry = PACKAGE.replace(".", "/") + "/core/config/VpnFailoverSettings.class"
        self.assertIn(entry, resources)
        with tempfile.TemporaryDirectory() as directory:
            for mutation in ("missing", "duplicate"):
                broken = Path(directory) / "broken-failover.jar"
                with warnings.catch_warnings():
                    warnings.simplefilter("ignore", UserWarning)
                    with zipfile.ZipFile(broken, "w") as output:
                        for name, data in resources.items():
                            if mutation != "missing" or name != entry: output.writestr(name, data)
                        if mutation == "duplicate": output.writestr(entry, resources[entry])
                with self.assertRaisesRegex(ValueError, "failover"):
                    verify(broken, version)

    def test_missing_or_duplicate_policy_types_and_examples_fail_before_runtime(self):
        artifact = next((ROOT / "build/libs").glob("*-all.jar"))
        with zipfile.ZipFile(artifact) as jar:
            version = json.loads(jar.read("velocity-plugin.json"))["version"]
            resources = {name: jar.read(name) for name in jar.namelist()}
        prefix = PACKAGE.replace(".", "/") + "/core/"
        selected = [prefix + name + ".class" for name in (
            "policy/ConnectionPolicy", "policy/ConnectionPolicy$Evaluation", "policy/PolicyJson",
            "policy/PolicyReplay", "policy/PolicyReplay$Snapshot", "policy/PolicyReplay$Case", "policy/PolicyReplay$Cases",
            "policy/PolicyShadow", "policy/PolicyShadow$Session", "policy/PolicyShadow$State", "policy/PolicyShadow$View", "commands/PolicyCommands")]
        selected.append("policy/examples.json")
        with tempfile.TemporaryDirectory() as directory:
            for name in selected:
                self.assertIn(name, resources)
                for mutation in ("missing", "duplicate"):
                    broken = Path(directory) / "broken-policy.jar"
                    with warnings.catch_warnings():
                        warnings.simplefilter("ignore", UserWarning)
                        with zipfile.ZipFile(broken, "w") as output:
                            for entry, data in resources.items():
                                if mutation != "missing" or entry != name: output.writestr(entry, data)
                            if mutation == "duplicate": output.writestr(name, resources[name])
                    with self.assertRaisesRegex(ValueError, "policy"):
                        verify(broken, version)

    def test_changed_policy_examples_fail_source_binding(self):
        artifact = next((ROOT / "build/libs").glob("*-all.jar"))
        with zipfile.ZipFile(artifact) as jar:
            version = json.loads(jar.read("velocity-plugin.json"))["version"]
            resources = {name: jar.read(name) for name in jar.namelist()}
        with tempfile.TemporaryDirectory() as directory:
            broken = Path(directory) / "changed-examples.jar"
            with zipfile.ZipFile(broken, "w") as output:
                for name, data in resources.items(): output.writestr(name, b'{"schema":1,"kind":"synthetic","cases":[]}' if name == "policy/examples.json" else data)
            with self.assertRaisesRegex(ValueError, "policy examples differ"):
                verify(broken, version)

    def test_missing_bundled_locale_fails_before_runtime(self):
        artifact = next((ROOT / "build/libs").glob("*-all.jar"))
        with zipfile.ZipFile(artifact) as jar:
            version = json.loads(jar.read("velocity-plugin.json"))["version"]
            resources = {name: jar.read(name) for name in jar.namelist()}
        catalog = PACKAGE.replace(".", "/") + "/core/messages/MessageCatalog.class"
        self.assertIn(catalog, resources)
        with tempfile.TemporaryDirectory() as directory:
            for omitted in ("translation/de.yml", "translation/es.yml", "translation/catalog/en.properties",
                            "translation/catalog/de.properties", "translation/catalog/es.properties"):
                broken = Path(directory) / "missing-locale.jar"
                with zipfile.ZipFile(broken, "w") as output:
                    for name, data in resources.items():
                        if name != omitted:
                            output.writestr(name, data)
                with self.assertRaisesRegex(ValueError, "bundled message resource"):
                    verify(broken, version)

    def test_optional_native_sdk_copies_are_rejected(self):
        artifacts = list((ROOT / "build/libs").glob("*-all.jar"))
        self.assertEqual(1, len(artifacts))
        artifact = artifacts[0]
        with zipfile.ZipFile(artifact) as jar:
            version = json.loads(jar.read("velocity-plugin.json"))["version"]
            harmless_class = jar.read(PACKAGE.replace(".", "/") + "/api/v1/ConnectionGuardApi.class")
        with tempfile.TemporaryDirectory() as directory:
            for name in ("org/geysermc/floodgate/api/FloodgateApi.class", "net/luckperms/api/LuckPerms.class",
                         "space/arim/libertybans/api/LibertyBans.class", "space/arim/omnibus/Omnibus.class",
                         "net/elytrium/limboapi/api/LimboFactory.class"):
                for prefix in ("", PACKAGE.replace(".", "/") + "/libs/"):
                    broken = Path(directory) / "duplicate-native-api.jar"
                    with zipfile.ZipFile(artifact) as source, zipfile.ZipFile(broken, "w") as target:
                        for entry in source.infolist(): target.writestr(entry, source.read(entry))
                        # Synthetic class bytes: no upstream SDK redistributed by the negative fixture.
                        target.writestr(prefix + name, harmless_class)
                    with self.assertRaisesRegex(ValueError, "Optional native SDK"):
                        verify(broken, version)

    def test_separate_agpl_addon_is_rejected(self):
        artifact = next((ROOT / "build/libs").glob("*-all.jar"))
        with tempfile.TemporaryDirectory() as directory:
            broken = Path(directory) / "bundled-addon.jar"
            with zipfile.ZipFile(artifact) as source, zipfile.ZipFile(broken, "w") as target:
                version = json.loads(source.read("velocity-plugin.json"))["version"]
                for entry in source.infolist(): target.writestr(entry, source.read(entry))
                # Harmless locally authored bytes, no upstream SDK in this fixture.
                target.writestr(PACKAGE.replace(".", "/") + "/addons/libertybans/LibertyBansReader.class",
                                source.read(PACKAGE.replace(".", "/") + "/api/v1/ConnectionGuardApi.class"))
            with self.assertRaisesRegex(ValueError, "separately licensed"):
                verify(broken, version)

    def test_separate_challenge_addon_is_rejected(self):
        artifact = next((ROOT / "build/libs").glob("*-all.jar"))
        with tempfile.TemporaryDirectory() as directory:
            broken = Path(directory) / "bundled-challenge-addon.jar"
            with zipfile.ZipFile(artifact) as source, zipfile.ZipFile(broken, "w") as target:
                version = json.loads(source.read("velocity-plugin.json"))["version"]
                for entry in source.infolist(): target.writestr(entry, source.read(entry))
                target.writestr(PACKAGE.replace(".", "/") + "/addons/limbo/VelocityChallengeAddon.class",
                                source.read(PACKAGE.replace(".", "/") + "/api/v1/ConnectionGuardApi.class"))
            with self.assertRaisesRegex(ValueError, "separately licensed"):
                verify(broken, version)

    def test_removed_or_duplicate_cloud_json_class_is_rejected(self):
        artifact = next((ROOT / "build/libs").glob("*-all.jar"))
        with zipfile.ZipFile(artifact) as jar:
            version = json.loads(jar.read("velocity-plugin.json"))["version"]
            self.assertIn(PACKAGE.replace(".", "/") + "/core/cloud/CloudSync.class", jar.namelist())
        verify(artifact, version)
        with tempfile.TemporaryDirectory() as directory:
            for dependency in ("Gson", "JsonElement", "stream/JsonReader"):
                name = PACKAGE.replace(".", "/") + "/libs/com/google/gson/" + dependency + ".class"
                for duplicate in (False, True):
                    broken = Path(directory) / "broken-cloud-json.jar"
                    with zipfile.ZipFile(artifact) as source, zipfile.ZipFile(broken, "w") as target:
                        for entry in source.infolist():
                            if duplicate or entry.filename != name:
                                target.writestr(entry, source.read(entry))
                        if duplicate:
                            with warnings.catch_warnings():
                                warnings.simplefilter("ignore", UserWarning)
                                target.writestr(name, source.read(name))
                    with self.assertRaisesRegex(ValueError, "Cloud JSON runtime dependency"):
                        verify(broken, version)

    def test_removed_or_duplicate_bstats_class_is_rejected(self):
        artifacts = list((ROOT / "build/libs").glob("*-all.jar"))
        self.assertEqual(1, len(artifacts), "Build the combined JAR before running artifact regression tests.")
        artifact = artifacts[0]
        with zipfile.ZipFile(artifact) as jar:
            version = json.loads(jar.read("velocity-plugin.json"))["version"]
        verify(artifact, version)
        with tempfile.TemporaryDirectory() as directory:
            for dependency in ("MetricsBase", "json/JsonObjectBuilder", "velocity/Metrics", "velocity/Metrics$Factory"):
                name = PACKAGE.replace(".", "/") + "/libs/org/bstats/" + dependency + ".class"
                for duplicate in (False, True):
                    broken = Path(directory) / "broken.jar"
                    with zipfile.ZipFile(artifact) as source, zipfile.ZipFile(broken, "w") as target:
                        for entry in source.infolist():
                            if duplicate or entry.filename != name:
                                target.writestr(entry, source.read(entry))
                        if duplicate:
                            with warnings.catch_warnings():
                                warnings.simplefilter("ignore", UserWarning)
                                target.writestr(name, source.read(name))
                    with self.assertRaisesRegex(ValueError, "bStats runtime dependency"):
                        verify(broken, version)


if __name__ == "__main__":
    unittest.main()
