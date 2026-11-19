#!/usr/bin/env python3
"""Build, install, or back up the FoodLog iPhone app on the paired iPhone.

Follows the Pacing Monitor installer pattern and shares Lodestone2's signing and
production-phone safeguards:

  install            build with the personal team, pull Lodestone Health and back up its
                     collector, back up FoodLog's Documents, install in place, then check
                     the FoodLog database survived (row counts never shrink).
  install --renewal  the same, but only for unchanged, previously verified source and only
                     when the new profile expires later. Used by Lodestone2's scheduler.
  backup             copy FoodLog's Documents (its SQLite database) to ios/local/backups.

It never uninstalls an app. FoodLog holds the only copy of logged food on the phone.
"""
import argparse
import fcntl
import hashlib
import os
import sqlite3
import shutil
import subprocess
import sys
import tempfile
import json
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
IOS = ROOT / "ios"
LOCAL = IOS / "local"
RECEIPT = LOCAL / "install.json"
BUNDLE = "org.lodestone.foodlog"
COPY_TIMEOUT = "300"
INSTALL_TIMEOUT = "300"
KEEP_BACKUPS = 30
COUNTED_TABLES = ("raw_entries", "food_items", "shortcuts", "daily_weights", "products")
# Deployable source: changes here require a deliberate install before automatic renewal resumes.
SOURCE_PARTS = ("FoodLog", "FoodLog.xcodeproj", "FoodLogCore/Sources", "FoodLogCore/Package.swift")

LODESTONE = ROOT.parent / "Lodestone2"
sys.path.insert(0, str(LODESTONE / "scripts"))
from apple_collector_signing import profile_renewed, provisioning_metadata, read_receipt, source_fingerprint, write_receipt  # noqa: E402
from apple_collector_profiles import fresh_profile_request  # noqa: E402
from install_apple_health_collector import backup_documents, harvest_health  # noqa: E402


class InstallDeferred(RuntimeError):
    pass


def fingerprint(root: Path = IOS) -> str:
    """Kept identical to Lodestone2/scripts/foodlog_signing.py."""
    digest = hashlib.sha256()
    for name in SOURCE_PARTS:
        path = root / name
        digest.update(name.encode())
        if path.is_dir():
            digest.update(source_fingerprint(path).encode())
        elif path.is_file():
            digest.update(hashlib.sha256(path.read_bytes()).hexdigest().encode())
        else:
            raise RuntimeError(f"FoodLog deployable source is missing {name}")
    return digest.hexdigest()


def run_json(command):
    with tempfile.NamedTemporaryFile(suffix=".json") as output:
        result = subprocess.run([*command, "--json-output", output.name], capture_output=True, text=True)
        if result.returncode:
            # Keep device details in private logs, not command output.
            log = LOCAL / "device-error.log"
            log.write_text(result.stdout + result.stderr)
            log.chmod(0o600)
            raise RuntimeError(f"Device command failed; details in {log}")
        return json.loads(Path(output.name).read_text())


def discover():
    devices = run_json(["xcrun", "devicectl", "list", "devices"])["result"]["devices"]
    phones = [d for d in devices if d.get("hardwareProperties", {}).get("deviceType") == "iPhone"
              and d.get("connectionProperties", {}).get("pairingState") == "paired"]
    if len(phones) != 1:
        raise RuntimeError("Expected one paired iPhone; supply --device explicitly.")
    return phones[0]["identifier"]


def build(fresh=False):
    derived = LOCAL / ("fresh-build" if fresh else "build")
    log = LOCAL / "device-build.log"
    with log.open("w") as output:
        result = subprocess.run(
            ["xcodebuild", "-project", str(IOS / "FoodLog.xcodeproj"), "-scheme", "FoodLog",
             "-configuration", "Debug", "-destination", "generic/platform=iOS", "-derivedDataPath", str(derived),
             "-allowProvisioningUpdates", "build"],
            stdout=output, stderr=subprocess.STDOUT)
    log.chmod(0o600)
    if result.returncode:
        raise RuntimeError(f"Build failed; see {log}")
    return derived / "Build/Products/Debug-iphoneos/FoodLog.app"


def installed(device):
    apps = run_json(["xcrun", "devicectl", "device", "info", "apps", "--device", device])["result"]["apps"]
    return any(app.get("bundleIdentifier") == BUNDLE for app in apps)


def copy_documents(device, destination: Path) -> Path:
    run_json(["xcrun", "devicectl", "device", "copy", "from", "--device", device,
              "--domain-type", "appDataContainer", "--domain-identifier", BUNDLE,
              "--source", "Documents", "--destination", str(destination), "--timeout", COPY_TIMEOUT, "--quiet"])
    return destination


def database_counts(documents: Path) -> dict | None:
    """Row counts from a copied database, read from a scratch copy so the backup stays untouched."""
    database = documents / "foodlog.sqlite"
    if not database.is_file():
        return None
    with tempfile.TemporaryDirectory(prefix="count-", dir=LOCAL) as scratch:
        for suffix in ("", "-wal", "-shm"):
            source = documents / f"foodlog.sqlite{suffix}"
            if source.is_file():
                shutil.copy2(source, Path(scratch) / source.name)
        connection = sqlite3.connect(Path(scratch) / "foodlog.sqlite")
        try:
            return {table: connection.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
                    for table in COUNTED_TABLES}
        finally:
            connection.close()


def backup(device, reason="backup") -> tuple[Path, dict | None]:
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    destination = LOCAL / "backups" / f"{stamp}-{reason}"
    destination.mkdir(parents=True, mode=0o700)
    documents = copy_documents(device, destination / "Documents")
    counts = database_counts(documents)
    (destination / "counts.json").write_text(json.dumps(counts, indent=2))
    prune_backups()
    return documents, counts


def prune_backups():
    """Keep the newest routine backups; install backups are kept until removed by hand."""
    routine = sorted(p for p in (LOCAL / "backups").iterdir() if p.is_dir() and p.name.endswith("-backup"))
    for old in routine[:-KEEP_BACKUPS]:
        shutil.rmtree(old)


def verify_preserved(device, before: dict | None):
    if before is None:
        return
    with tempfile.TemporaryDirectory(prefix="verify-", dir=LOCAL) as temporary:
        after = database_counts(copy_documents(device, Path(temporary) / "Documents"))
    if after is None:
        raise RuntimeError("FoodLog database vanished across installation; backup retained")
    shrunk = [table for table, count in before.items() if after.get(table, 0) < count]
    if shrunk:
        raise RuntimeError(f"FoodLog rows shrank across installation ({', '.join(shrunk)}); backup retained")


def install(device, renewal=False):
    previous = read_receipt(RECEIPT) if RECEIPT.is_file() else {}
    source = fingerprint()
    if renewal and previous.get("sourceSha256") != source:
        raise RuntimeError("FoodLog source changed or is unverified; use a deliberate manual install")
    app = build()
    metadata = provisioning_metadata(app / "embedded.mobileprovision")
    if renewal and not profile_renewed(previous, metadata):
        with fresh_profile_request(app / "embedded.mobileprovision", backup_root=LOCAL / "profile-backups"):
            app = build(fresh=True)
            metadata = provisioning_metadata(app / "embedded.mobileprovision")
        if not profile_renewed(previous, metadata):
            raise RuntimeError("FoodLog profile expiry was not extended; existing app left untouched")
    if fingerprint() != source:
        raise RuntimeError("FoodLog source changed during build; refusing installation")
    # Production-phone safeguard shared with the other personal apps.
    harvest_health(device)
    backup_documents(device)
    present = installed(device)
    if renewal and not present:
        raise RuntimeError("FoodLog is no longer installed; automatic renewal will not reinstall a removed app")
    before = backup(device, "install")[1] if present else None
    run_json(["xcrun", "devicectl", "device", "install", "app", "--device", device,
              "--timeout", INSTALL_TIMEOUT, "--quiet", str(app)])
    verify_preserved(device, before)
    harvest_health(device)
    write_receipt({"bundle": BUNDLE, **metadata}, source, path=RECEIPT)
    print("FoodLog installed in place; preservation checks passed." if present else "FoodLog installed.")
    print("Signing expiry:", metadata["profileExpiresAt"])


def push_import(device, export: Path):
    """Stage an Android export in the app's Documents/Import folder for its import button."""
    if not export.is_file():
        raise RuntimeError(f"{export} not found")
    run_json(["xcrun", "devicectl", "device", "copy", "to", "--device", device,
              "--domain-type", "appDataContainer", "--domain-identifier", BUNDLE,
              "--source", str(export), "--destination", f"Documents/Import/{export.name}",
              "--timeout", COPY_TIMEOUT, "--quiet"])
    print(f"Staged {export.name}; open FoodLog → Settings → Import.")


def main():
    os.umask(0o077)
    LOCAL.mkdir(parents=True, exist_ok=True, mode=0o700)
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("action", choices=["build", "install", "backup", "push-import", "fingerprint"])
    parser.add_argument("--device")
    parser.add_argument("--renewal", action="store_true",
                        help="Require unchanged verified source and a later usable expiry")
    parser.add_argument("--export", type=Path, help="Android export JSON for push-import")
    args = parser.parse_args()
    if args.renewal and args.action != "install":
        parser.error("--renewal is only valid with install")
    try:
        if args.action == "fingerprint":
            print(fingerprint())
        elif args.action == "backup":
            documents, counts = backup(args.device or discover())
            print(f"FoodLog backup saved to {documents.parent} ({counts})")
        elif args.action == "push-import":
            if args.export is None:
                parser.error("push-import needs --export")
            push_import(args.device or discover(), args.export)
        else:
            with (LOCAL / "install.lock").open("a+") as lock:
                try:
                    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError:
                    raise InstallDeferred("Another FoodLog build or install is in progress")
                if args.action == "build":
                    build()
                    print("Signed iPhone build succeeded.")
                else:
                    install(args.device or discover(), renewal=args.renewal)
        return 0
    except InstallDeferred as error:
        print(str(error))
        return 75
    except (OSError, RuntimeError, subprocess.SubprocessError, ValueError, KeyError, sqlite3.Error) as error:
        print(f"FoodLog: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
