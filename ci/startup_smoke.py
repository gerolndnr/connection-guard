#!/usr/bin/env python3
"""Start the actual combined JAR alone on a pinned runtime; retain hash-bound evidence.

Paper requires an explicit EULA decision. No server is started merely by importing
this module. Fixtures listen only on loopback, disable telemetry, use no accounts,
and stop their owned process even when the plugin fails to enable.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import queue
import re
import shutil
import socket
import subprocess
import threading
import time
import zipfile

ROOT = Path(__file__).resolve().parents[1]
RUNTIMES = {
    "paper": {"name": "paper-1.21.11-132.jar", "sha256": "5ffef465eeeb5f2a3c23a24419d97c51afd7dbb4923ff42df9a3f58bba1ccfba"},
    "velocity": {"name": "velocity-3.4.0-566.jar", "sha256": "fb599cbda6a6d01decce5e281f71f51cae7cacffcfafca32a09601f407b0583e"},
}
FAILURES = ("NoClassDefFoundError", "ClassNotFoundException", "Error occurred while enabling",
            "Error occurred while disabling", "Unable to load plugin", "Could not load plugin",
            "Unable to initialize plugin", "Exception in thread")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def require(condition, message):
    if not condition:
        raise ValueError(message)


def clean_source():
    status = subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True)
    require(not status.strip(), "Commit the source and fixture before recording release evidence.")
    return subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()


def download_runtime(platform, directory):
    spec = RUNTIMES[platform]
    target = directory / spec["name"]
    url = "https://fill-data.papermc.io/v1/objects/" + spec["sha256"] + "/" + spec["name"]
    subprocess.run(["curl", "--proto", "=https", "--fail", "--location", "--max-time", "120",
                    "--max-filesize", "60000000", "--user-agent",
                    "ConnectionGuardCI/1 (+https://github.com/gerolndnr/connection-guard)",
                    "--output", str(target), url], check=True, timeout=130)
    return target


def setup(directory, platform, artifact, runtime, accept_eula):
    require(platform != "paper" or accept_eula, "Paper requires explicit --accept-eula after a conscious EULA decision.")
    require(digest(runtime) == RUNTIMES[platform]["sha256"], "Runtime SHA-256 differs from the pinned official build.")
    plugins = directory / "plugins"
    plugins.mkdir()
    shutil.copyfile(artifact, plugins / "connection-guard.jar")
    (plugins / "bStats").mkdir()
    with zipfile.ZipFile(artifact) as jar:
        version = json.loads(jar.read("velocity-plugin.json"))["version"]
    if platform == "velocity":
        with zipfile.ZipFile(runtime) as jar:
            config = jar.read("default-velocity.toml").decode()
        config = re.sub(r'^bind = "[^"]+"$', 'bind = "127.0.0.1:0"', config, flags=re.M)
        config = config.replace("online-mode = true", "online-mode = false")
        config = config.replace("force-key-authentication = true", "force-key-authentication = false")
        config = config.replace('player-info-forwarding-mode = "modern"', 'player-info-forwarding-mode = "none"')
        (directory / "velocity.toml").write_text(config)
        (plugins / "bStats/config.txt").write_text("enabled=false\n")
    else:
        with socket.socket() as reserve:
            reserve.bind(("127.0.0.1", 0))
            port = reserve.getsockname()[1]
        (directory / "eula.txt").write_text("# Explicit fixture EULA decision supplied by operator\neula=true\n")
        (directory / "server.properties").write_text(
            "server-ip=127.0.0.1\nserver-port=" + str(port) + "\nonline-mode=false\nenforce-secure-profile=false\n"
            "max-players=4\nenable-rcon=false\nenable-query=false\nview-distance=2\nsimulation-distance=2\n"
            "level-type=minecraft:flat\ngenerate-structures=false\nspawn-protection=0\n"
            'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1}],"biome":"minecraft:plains","features":false,"lakes":false}\n')
        (directory / "config").mkdir()
        (directory / "config/paper-global.yml").write_text("_version: 31\nchunk-system:\n  io-threads: 1\n  worker-threads: 2\n")
        (plugins / "bStats/config.yml").write_text("enabled: false\n")
    return version


def run(artifact, platform, runtime, directory, java, accept_eula, source_commit):
    version = setup(directory, platform, artifact, runtime, accept_eula)
    transcript = []
    lines = queue.Queue()
    arguments = [java, "-Xms128m", "-Xmx768m" if platform == "paper" else "-Xmx256m",
                 "-Dio.netty.eventLoopThreads=2", "-Dterminal.jline=false", "-Dterminal.ansi=false", "-jar", str(runtime)]
    if platform == "paper":
        arguments.append("--nogui")
    process = subprocess.Popen(arguments, cwd=directory, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, text=True, bufsize=1)

    def consume():
        for line in process.stdout:
            line = re.sub(r"\x1b\[[0-9;]*[A-Za-z]", "", line)
            transcript.append(line)
            lines.put(line)
        lines.put(None)

    reader = threading.Thread(target=consume, daemon=True)
    reader.start()

    def wait(marker, seconds):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            try:
                line = lines.get(timeout=.2)
            except queue.Empty:
                continue
            require(line is not None, "Runtime exited before: " + marker)
            require(not any(failure in line for failure in FAILURES), "Plugin startup/runtime error; inspect console.log.")
            if marker in line:
                return
        raise ValueError("Runtime timed out before: " + marker)

    try:
        wait("Done (", 180)
        for command, marker in (("cg help", "Overview of commands"), ("cg reload", "Config has been reloaded!")):
            process.stdin.write(command + "\n")
            process.stdin.flush()
            wait(marker, 30)
        process.stdin.write("stop\n" if platform == "paper" else "shutdown\n")
        process.stdin.flush()
        process.wait(timeout=45)
        reader.join(timeout=5)
        require(not reader.is_alive(), "Runtime output did not finish.")
        require(process.returncode == 0, "Runtime did not stop cleanly.")
        require(not any(failure in "".join(transcript) for failure in FAILURES), "Runtime logged a startup or shutdown error.")
        log = directory / "console.log"
        log.write_text("".join(transcript))
        result = {"schema": 1, "kind": "connection-guard-startup", "source_commit": source_commit,
                  "artifact_sha256": digest(artifact), "version": version, "platform": platform,
                  "runtime": RUNTIMES[platform]["name"], "runtime_sha256": digest(runtime),
                  "plugin_enabled": True, "help_passed": True, "reload_passed": True, "shutdown_passed": True,
                  "console_sha256": digest(log), "other_plugins": [], "telemetry_enabled": False,
                  "real_player_login_tested": False, "all_platform_versions_tested": False}
        (directory / "result.json").write_text(json.dumps(result, indent=2) + "\n")
        return result
    finally:
        if process.poll() is None:
            try:
                process.stdin.write("stop\n" if platform == "paper" else "shutdown\n")
                process.stdin.flush()
                process.wait(timeout=30)
            except (BrokenPipeError, subprocess.TimeoutExpired):
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
        reader.join(timeout=5)
        (directory / "console.log").write_text("".join(transcript))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifact", type=Path, required=True)
    parser.add_argument("--platform", choices=RUNTIMES, required=True)
    parser.add_argument("--runtime", type=Path, help="Already downloaded, hash-checked official runtime; otherwise download.")
    parser.add_argument("--work-dir", type=Path, required=True, help="A new, isolated fixture directory.")
    parser.add_argument("--java", default="java")
    parser.add_argument("--accept-eula", action="store_true")
    args = parser.parse_args()
    require(args.platform != "paper" or args.accept_eula, "Paper requires explicit --accept-eula after a conscious EULA decision.")
    source_commit = clean_source()
    artifact = args.artifact.resolve(strict=True)
    require(artifact.is_relative_to(ROOT / "build/libs"), "Use the combined JAR built in this source checkout.")
    directory = args.work_dir.resolve()
    require(not directory.exists(), "Fixture directory already exists; inspect evidence before retrying.")
    directory.mkdir(mode=0o700, parents=True)
    runtime = args.runtime.resolve(strict=True) if args.runtime else download_runtime(args.platform, directory)
    result = run(artifact, args.platform, runtime, directory, args.java, args.accept_eula, source_commit)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.SubprocessError, zipfile.BadZipFile, KeyError) as error:
        print("Startup qualification failed: " + str(error), file=__import__("sys").stderr)
        raise SystemExit(1)
