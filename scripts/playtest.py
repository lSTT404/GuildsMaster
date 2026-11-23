"""Build a test adapter, run real offline clients on loopback, and verify SQLite effects."""
import argparse
import os
import re
from pathlib import Path
import shutil
import sqlite3
import subprocess

from smoke import ROOT, Server, prepare

CURRENCIES = """currencies:
  money: {enabled: true, display-name: Coins, creation-cost: 10, type: VAULT}
  tokens:
    enabled: true
    display-name: Tokens
    creation-cost: 5
    type: COMMAND
    withdraw-command: 'gmtestpay withdraw tokens {player} {amount} {transaction}'
    refund-command: 'gmtestpay refund tokens {player} {amount} {transaction}'
  crystals:
    enabled: true
    display-name: Crystals
    creation-cost: 7
    type: COMMAND
    withdraw-command: 'gmtestpay withdraw crystals {player} {amount} {transaction}'
    refund-command: 'gmtestpay refund crystals {player} {amount} {transaction}'
"""


def setup(args):
    folder = prepare(args)
    for sub in ["cache", "versions", "libraries"]:
        source = args.server.resolve().parent / sub
        if source.is_dir() and not (folder / sub).is_dir():
            shutil.copytree(source, folder / sub)
    plugins = folder / "plugins"
    config = plugins / "GuildMaster"
    config.mkdir(exist_ok=True)
    defaults = ROOT / "src/main/resources"
    (config / "currencies.yml").write_text(CURRENCIES, encoding="utf-8")
    (config / "config.yml").write_text(
        (defaults / "config.yml").read_text(encoding="utf-8").replace("alliance: 5", "alliance: 0"), encoding="utf-8"
    )
    (config / "mechanics.yml").write_text((defaults / "mechanics.yml").read_text(encoding="utf-8"), encoding="utf-8")
    (config / "ranking.yml").write_text(
        (defaults / "ranking.yml").read_text(encoding="utf-8").replace("recalculate-seconds: 60", "recalculate-seconds: 10"),
        encoding="utf-8"
    )
    (config / "permissions.yml").write_text(
        (defaults / "permissions.yml").read_text(encoding="utf-8").replace("luckperms-groups: {}", """  builder:
    display-name: Builder
    priority: 10
    color: yellow
    prefix: ''
    actions: [info, home, sethome, leave]
luckperms-groups: {}
"""), encoding="utf-8"
    )
    if args.luckperms:
        shutil.copyfile(args.luckperms, plugins / "LuckPerms.jar")
        roles = config / "permissions.yml"
        roles.write_text(roles.read_text(encoding="utf-8").replace("luckperms-groups: {}", """  veteran:
    display-name: Veteran
    priority: 20
    color: green
    prefix: ''
    actions: [info, home, chat, leave]
luckperms-groups: {default: veteran}
"""), encoding="utf-8")
    if (config / "data/guildmaster.db").exists():
        raise ValueError("Use a fresh playtest directory")
    shutil.copyfile(args.vault, plugins / "Vault.jar")
    probe = folder / "probe"
    probe.mkdir(exist_ok=True)
    sources = ROOT / "scripts/fixtures"
    java_home = Path(args.java).resolve().parent
    exe = ".exe" if os.name == "nt" else ""
    classpath = os.pathsep.join(str(p.resolve()) for p in [args.api, args.vault, args.plugin])
    subprocess.run([java_home / ("javac"+exe), "--release", "21", "-cp", classpath, "-d", probe, sources / "Probe.java"], check=True)
    shutil.copyfile(sources / "plugin.yml", probe / "plugin.yml")
    subprocess.run([java_home / ("jar"+exe), "--create", "--file", plugins / "GuildMasterProbe.jar", "-C", probe, "."], check=True)
    return folder, config


def run(args):
    folder, config = setup(args)
    db_path = config / "data/guildmaster.db"
    live = Server(folder, args.java, args.port)
    with live:
        live.expect("GuildMaster ready", 300)
        with sqlite3.connect(db_path) as db:
            db.execute("""
                CREATE TRIGGER force_compensation BEFORE INSERT ON guild
                WHEN NEW.name='Refundable' BEGIN SELECT RAISE(ABORT,'smoke-injected failure'); END
            """)
        environment = {**os.environ, "NODE_PATH": str(args.node_modules.resolve()),
                       "GM_TEST_PAPI": "1" if args.placeholder else "0",
                       "GM_TEST_LUCKPERMS": "1" if args.luckperms else "0"}
        subprocess.run([args.node, ROOT / "scripts/playtest.cjs", str(args.port)], env=environment, check=True, timeout=150)
        with sqlite3.connect(db_path) as db:
            assert db.execute("SELECT name FROM guild WHERE archived=0 ORDER BY name").fetchall() == [
                ("AuroraPrime",), ("Copper",), ("Ember",)
            ]
            assert dict(db.execute("SELECT name_key,state FROM payment")) == {
                "aurora": "done", "copper": "done", "ember": "done",
                "rejected": "failed", "unknown": "review", "refundable": "refunded"
            }
            assert db.execute("SELECT role FROM member WHERE name='RioBot'").fetchone() == ("leader",)
            assert db.execute("SELECT count(*) FROM relation WHERE status='active'").fetchone() == (2,)
            assert db.execute("SELECT count(*) FROM territory").fetchone() == (1,)
            assert db.execute("SELECT count(*) FROM pvp WHERE tracked=1").fetchone() == (1,)
            assert db.execute("SELECT count(*) FROM war_kill").fetchone() == (1,)
            db.execute("DROP TRIGGER force_compensation")
        assert len([line for line in live.log if "BALANCE vault AdaBot withdraw" in line]) == 1
        assert any("BALANCE tokens RefundBot refund 5.0 = 1000.0" in line for line in live.log)
        if args.placeholder:
            live.send("papi parse --null GM_TOP:%top1_guilds% GM_SCORE:%top1_guilds_score%")
            live.expect(r"GM_TOP:\w+ GM_SCORE:\d+\.\d+", 20)
        mechanics = config / "mechanics.yml"
        original = mechanics.read_text(encoding="utf-8")
        mechanics.write_text(re.sub(r"^  (?!guilds:)([a-z_]+): true$", r"  \1: false", original, flags=re.M), encoding="utf-8")
        live.send("guildadmin reload")
        live.expect("Configuration reloaded", 20)
        try:
            subprocess.run([args.node, ROOT / "scripts/playtest.cjs", str(args.port), "disabled"],
                           env=environment, check=True, timeout=80)
        finally:
            mechanics.write_text(original, encoding="utf-8")
        live.send("guildadmin reload")
        live.expect("Configuration reloaded", 20)
    with live:
        live.expect("GuildMaster ready", 180)
        live.send("guildadmin info")
        live.expect("3 active guilds, 5 members", 20)
    print("PASS SQLite state, single Vault withdrawal, actual COMMAND refund, and restart")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--folder", type=Path, required=True)
    parser.add_argument("--server", type=Path, required=True)
    parser.add_argument("--eula", type=Path, required=True)
    parser.add_argument("--vault", type=Path, required=True)
    parser.add_argument("--api", type=Path, required=True)
    parser.add_argument("--java", required=True)
    parser.add_argument("--node", default="node")
    parser.add_argument("--node-modules", type=Path, required=True)
    parser.add_argument("--port", type=int, default=25597)
    parser.add_argument("--plugin", type=Path, default=ROOT / "target/GuildMaster.jar")
    parser.add_argument("--placeholder", type=Path)
    parser.add_argument("--luckperms", type=Path)
    args = parser.parse_args()
    args.legacy = None
    run(args)
