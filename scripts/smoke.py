"""Run a GuildMaster boot, reload, backup and restart on a disposable local server."""
import argparse
import hashlib
import queue
import re
import shutil
import sqlite3
import subprocess
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class Server:
    def __init__(self, folder, java, port):
        self.folder = folder
        self.java = java
        self.port = port
        self.out = queue.Queue()
        self.log = []

    def __enter__(self):
        self.log.clear()
        self.out = queue.Queue()
        self.process = subprocess.Popen(
            [self.java, "-Xms256M", "-Xmx768M", "-XX:ActiveProcessorCount=2", "-Dfile.encoding=UTF-8",
             "-jar", "server.jar", "nogui"],
            cwd=self.folder, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace"
        )
        threading.Thread(target=self._read, daemon=True).start()
        return self

    def _read(self):
        for line in self.process.stdout:
            line = line.rstrip()
            self.log.append(line)
            self.out.put(line)
        self.out.put(None)

    def expect(self, expression, timeout=120):
        match = re.compile(expression)
        until = time.monotonic() + timeout
        while time.monotonic() < until:
            try:
                line = self.out.get(timeout=max(0.01, until-time.monotonic()))
            except queue.Empty:
                break
            if line is None:
                raise AssertionError(f"Server exited waiting for {expression!r}")
            if "Cannot start GuildMaster" in line or "Cannot initialize GuildMaster storage" in line:
                raise AssertionError(line)
            result = match.search(line)
            if result:
                return result
        raise AssertionError(f"Timed out waiting for {expression!r}")

    def send(self, text):
        self.process.stdin.write(text + "\n")
        self.process.stdin.flush()

    def __exit__(self, kind, failure, traceback):
        if self.process.poll() is None:
            try:
                self.send("stop")
                self.process.wait(timeout=30)
            except (OSError, subprocess.TimeoutExpired):
                if self.process.poll() is None:
                    self.process.kill()
                self.process.wait(timeout=10)
        with (self.folder / "smoke.log").open("a", encoding="utf-8") as output:
            output.write("\n".join(self.log) + "\n")
        if kind:
            print("\n".join(self.log[-32:]))
        return False


def prepare(args):
    folder = args.folder.resolve()
    folder.mkdir(parents=True, exist_ok=True)
    server = folder / "server.jar"
    if args.server.resolve() != server:
        shutil.copyfile(args.server, server)
    if args.eula:
        eula = args.eula.resolve()
        if "eula=true" not in eula.read_text().lower():
            raise ValueError("Supply an accepted eula.txt for this test server")
        shutil.copyfile(eula, folder / "eula.txt")
    plugins = folder / "plugins"
    plugins.mkdir(exist_ok=True)
    shutil.copyfile(args.plugin, plugins / "GuildMaster.jar")
    if args.placeholder:
        shutil.copyfile(args.placeholder, plugins / "PlaceholderAPI.jar")
    (folder / "server.properties").write_text(
        f"server-ip=127.0.0.1\nserver-port={args.port}\nonline-mode=false\nenforce-secure-profile=false\n"
        "gamemode=creative\ndifficulty=peaceful\nview-distance=3\nsimulation-distance=3\n"
        'spawn-protection=0\nlevel-type=minecraft:flat\ngenerator-settings={"layers":[{"block":"minecraft:bedrock","height":1},'
        '{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}\n',
        encoding="utf-8"
    )
    if args.legacy:
        legacy_path = plugins / "VaelisKRL"
        legacy_path.mkdir(exist_ok=True)
        # backup() includes committed WAL pages and never opens the original for writes.
        with sqlite3.connect(args.legacy.resolve().as_uri() + "?mode=ro", uri=True) as source:
            with sqlite3.connect(legacy_path / "guilds.db") as target:
                source.backup(target)
    return folder


def run(args):
    digest = None
    if args.legacy:
        with args.legacy.open("rb") as source:
            digest = hashlib.file_digest(source, "sha256").hexdigest()
    folder = prepare(args)
    live = Server(folder, args.java, args.port)
    with live:
        live.expect("GuildMaster ready", 300)
        live.send("guildadmin debug")
        probe = live.expect(r"SQLite schema (\d+)", 20)
        live.send("guildadmin reload")
        live.expect("Configuration reloaded", 20)
        config = folder / "plugins/GuildMaster/config.yml"
        original = config.read_text(encoding="utf-8")
        try:
            invalid = original.replace("max-members: 15", "max-members: broken")
            assert invalid != original
            config.write_text(invalid, encoding="utf-8")
            live.send("guildadmin reload")
            live.expect(r"config.yml: guild.max-members", 20)
        finally:
            config.write_text(original, encoding="utf-8")
        live.send("guildadmin backup")
        live.expect(r"Backup saved as manual-[-a-z0-9]+\.db", 20)
        if args.legacy:
            live.send("guildadmin migrate check")
            check = int(live.expect(r"(\d+) rows can be imported", 30)[1])
            live.send("guildadmin migrate apply")
            token = live.expect(r"migrate apply ([a-f0-9]{8})", 20)[1]
            live.send(f"guildadmin migrate apply {token}")
            assert int(live.expect(r"(\d+) rows imported", 30)[1]) == check
            live.send("guildadmin migrate check")
            live.expect("0 rows can be imported", 30)
        if args.placeholder:
            live.send("papi parse --null GM_TOP=%top1_guilds%")
            live.expect(r"GM_TOP=(?!%top1_guilds%)[^ ]+", 20)
    with live:
        live.expect("GuildMaster ready", 180)
        live.send("guildadmin info")
        summary = live.expect(r"(\d+) active guilds, (\d+) members", 20)
    if args.legacy:
        with args.legacy.open("rb") as source:
            assert hashlib.file_digest(source, "sha256").hexdigest() == digest
    print(f"PASS {args.folder.name}: SQLite schema {probe[1]}, restart {summary[1]} guilds / {summary[2]} members.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--folder", type=Path, required=True, help="Dedicated test server directory")
    parser.add_argument("--server", type=Path, required=True)
    parser.add_argument("--java", default="java")
    parser.add_argument("--port", type=int, default=25591)
    parser.add_argument("--plugin", type=Path, default=ROOT / "target" / "GuildMaster.jar")
    parser.add_argument("--eula", type=Path, help="An already accepted eula.txt")
    parser.add_argument("--legacy", type=Path, help="Optional VaelisKRL database to snapshot and import")
    parser.add_argument("--placeholder", type=Path, help="Optional PlaceholderAPI jar")
    run(parser.parse_args())
