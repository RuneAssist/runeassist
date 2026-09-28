#!/usr/bin/env python3
"""Plugin Hub rules check: the things RuneLite's Hub reviewers and packager reject.

    python3 scripts/hub_rules.py                  # check (CI runs this on every PR)
    python3 scripts/hub_rules.py --update-baseline

Sources: reviewer comments on runelite/plugin-hub PRs ("use of X is not allowed"),
the packager's disallowed-apis.txt and manifest checks in runelite/plugin-hub-tooling.
docs/hub-rules.md explains each rule and its replacement.

Existing violations are listed in scripts/hub-rules-baseline.txt with a count per
file and rule. The check fails when a count grows (new violation) or shrinks
(baseline is stale: run --update-baseline so the number only ever goes down).
"""
import os
import re
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
BASELINE = ROOT / "scripts" / "hub-rules-baseline.txt"
MAIN = ROOT / "src" / "main" / "java"
TEST = ROOT / "src" / "test" / "java"

# (id, regex, applies to tests too, message)
RULES = [
    ("thread-interrupt", r"\.interrupt\(\)", True,
     "thread interrupt is not allowed; let the task finish, use acquireUninterruptibly / cancel(false)"),
    ("shutdown-now", r"\.shutdownNow\(\)", True,
     "shutdownNow() interrupts workers; use shutdown()"),
    ("thread-sleep", r"\bThread\.sleep\(", False,
     "Thread.sleep is not allowed; schedule on the ScheduledExecutorService instead"),
    ("reflection", r"java\.lang\.reflect|\bClass\.forName\(|\.getDeclared(?:Method|Field|Constructor)s?\(|\.setAccessible\(|com\.google\.gson\.reflect", False,
     "reflection is not allowed (Gson TypeToken counts); parse with JsonObject/JsonArray or typed classes"),
    ("getenv", r"\bSystem\.getenv\(", False,
     "System.getenv is not allowed; use a JVM system property"),
    ("awt-desktop", r"java\.awt\.Desktop\b|\bDesktop\.getDesktop\(", False,
     "java.awt.Desktop is not allowed; use LinkBrowser.browse"),
    ("linkbrowser-open", r"\bLinkBrowser\.open\(", False,
     "LinkBrowser.open is restricted; use LinkBrowser.browse (http/https only)"),
    ("keyboard-focus-manager", r"\bKeyboardFocusManager\b", False,
     "KeyboardFocusManager is not allowed; use RuneLite's KeyManager"),
    ("system-io", r"\bSystem\.(?:out|err)\.print|\.printStackTrace\(", False,
     "system i/o is not allowed; use the slf4j logger"),
    ("javax-sound", r"\bjavax\.sound\b", False,
     "javax.sound is not allowed; use net.runelite.client.audio.AudioPlayer"),
    ("runtime-subprocess", r"\bRuntime\.getRuntime\(|\bProcessBuilder\b|\bavailableProcessors\(|\bSystem\.exit\(", False,
     "Runtime, subprocesses, availableProcessors and System.exit are not allowed"),
    ("client-actions", r"\.menuAction\(|\.hopToWorld\(|\.invokeMenuAction\(", False,
     "client.menuAction / hopToWorld are not allowed"),
    ("discord-internals", r"\bDiscordUser\b|\bDiscordService\b|net\.runelite\.client\.discord", False,
     "RuneLite's Discord integration is not allowed"),
    ("account-internals", r"net\.runelite\.client\.account\.", False,
     "RuneLite account/session classes are not allowed"),
    ("new-gson", r"\bnew\s+Gson(?:Builder)?\s*\(", False,
     "do not construct Gson; @Inject the client's Gson and use gson.newBuilder()"),
    ("new-okhttp", r"\bnew\s+OkHttpClient(?:\.Builder)?\s*\(", False,
     "do not construct OkHttpClient; @Inject the client's OkHttpClient"),
    ("widget-info", r"\bWidgetInfo\b|\bWidgetID\b", False,
     "WidgetInfo/WidgetID are terminally deprecated; use ComponentID/InterfaceID"),
    ("client-getvar", r"\bclient\.getVar\(", False,
     "client.getVar is terminally deprecated; use getVarbitValue/getVarpValue"),
    ("file-io", r"\bjava\.io\.File\b|\bnew\s+File\s*\(|\bjava\.nio\.file\.(?:Files|Path|Paths)\b|\bFiles\.\w+\(|\bPaths\.get\(|\bFile(?:Reader|Writer|InputStream|OutputStream)\b|\bRUNELITE_DIR\b", False,
     "file i/o must go through net.runelite.client.util.Filepath (Plugin.getPluginDirectory()), not java.io/java.nio"),
    ("runelite-package", r"^\s*package\s+net\.runelite\b", True,
     "plugin code must not live in the net.runelite package"),
]

ALLOWED_PROPS = {"displayName", "description", "tags", "author", "plugins", "build", "support", "version"}
REQUIRED_PROPS = {"displayName", "description", "author", "plugins", "build"}


def strip_comments(text):
    """Drop // and /* */ comments so prose about a rule does not trip it."""
    out, i, n, in_block, in_str, quote = [], 0, len(text), False, False, ""
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if in_block:
            if c == "*" and nxt == "/":
                in_block = False
                i += 2
                continue
            out.append("\n" if c == "\n" else " ")
            i += 1
            continue
        if in_str:
            out.append(c)
            if c == "\\":
                out.append(nxt)
                i += 2
                continue
            if c == quote:
                in_str = False
            i += 1
            continue
        if c == "/" and nxt == "*":
            in_block = True
            i += 2
            continue
        if c == "/" and nxt == "/":
            while i < n and text[i] != "\n":
                i += 1
            continue
        if c in ('"', "'"):
            in_str, quote = True, c
        out.append(c)
        i += 1
    return "".join(out)


def java_files(base):
    return sorted(p for p in base.rglob("*.java")) if base.exists() else []


def scan():
    """Return {(rule, relpath): [(line, snippet), ...]}."""
    hits = {}
    for base, is_test in ((MAIN, False), (TEST, True)):
        for path in java_files(base):
            rel = path.relative_to(ROOT).as_posix()
            code = strip_comments(path.read_text(encoding="utf-8", errors="replace"))
            lines = code.split("\n")
            for rid, pattern, tests_too, _ in RULES:
                if is_test and not tests_too:
                    continue
                rx = re.compile(pattern)
                for no, line in enumerate(lines, 1):
                    if rx.search(line):
                        hits.setdefault((rid, rel), []).append((no, line.strip()[:120]))
    return hits


def read_baseline():
    base = {}
    if BASELINE.exists():
        for raw in BASELINE.read_text().splitlines():
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            rid, rel, count = line.split("\t")
            base[(rid, rel)] = int(count)
    return base


def write_baseline(hits):
    rows = ["# Existing Plugin Hub rule violations, grandfathered until fixed.",
            "# rule<TAB>file<TAB>count. Regenerate with: python3 scripts/hub_rules.py --update-baseline",
            "# The count may only go down; a new hit anywhere fails the check."]
    for (rid, rel), items in sorted(hits.items()):
        rows.append(f"{rid}\t{rel}\t{len(items)}")
    BASELINE.write_text("\n".join(rows) + "\n")


def png_size(path):
    with path.open("rb") as f:
        head = f.read(24)
    if len(head) < 24 or head[:8] != b"\x89PNG\r\n\x1a\n":
        return None
    return struct.unpack(">II", head[16:24])


def manifest_problems():
    problems = []
    props_path = ROOT / "runelite-plugin.properties"
    if not props_path.exists():
        return [(props_path, 1, "runelite-plugin.properties is missing")]
    props = {}
    for no, raw in enumerate(props_path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        props[key.strip()] = value.strip()
        if key.strip() not in ALLOWED_PROPS:
            problems.append((props_path, no, f"unknown key '{key.strip()}' in runelite-plugin.properties"))
    for key in sorted(REQUIRED_PROPS - props.keys()):
        problems.append((props_path, 1, f"'{key}' must be set in runelite-plugin.properties"))
    if props.get("build") not in (None, "standard", "gradle"):
        problems.append((props_path, 1, "build must be 'standard' or 'gradle'"))
    for cls in re.split(r"[,:;]", props.get("plugins", "")):
        cls = cls.strip()
        if cls and not (MAIN / (cls.replace(".", "/") + ".java")).exists():
            problems.append((props_path, 1, f"plugin class {cls} has no source file"))
    lic = ROOT / "LICENSE"
    if not lic.exists():
        problems.append((lic, 1, "LICENSE is missing (BSD 2-Clause recommended)"))
    elif "BSD 2-Clause" not in lic.read_text(encoding="utf-8", errors="replace")[:200]:
        problems.append((lic, 1, "LICENSE should be the verbatim BSD 2-Clause text"))
    icon = ROOT / "icon.png"
    if icon.exists():
        if icon.stat().st_size > 256 * 1024:
            problems.append((icon, 1, "icon.png is above the Hub's 256KiB limit"))
        dims = png_size(icon)
        if dims is None:
            problems.append((icon, 1, "icon.png is not a valid PNG"))
        elif dims[0] * dims[1] > 50 * 100:
            problems.append((icon, 1, f"icon.png is {dims[0]}x{dims[1]}; the Hub wants at most 48x72"))
    return problems


def emit(kind, path, line, msg):
    rel = Path(path).resolve().relative_to(ROOT).as_posix() if Path(path).is_absolute() else path
    if os.environ.get("GITHUB_ACTIONS"):
        print(f"::{kind} file={rel},line={line}::{msg}")
    print(f"{rel}:{line}: {kind}: {msg}")


def main(argv):
    hits = scan()
    if "--update-baseline" in argv:
        write_baseline(hits)
        print(f"baseline written: {sum(len(v) for v in hits.values())} grandfathered hits in {len(hits)} file/rule pairs")
        return 0
    messages = {rid: msg for rid, _, _, msg in RULES}
    baseline = read_baseline()
    failures = 0
    for key in sorted(set(hits) | set(baseline)):
        rid, rel = key
        found = hits.get(key, [])
        allowed = baseline.get(key, 0)
        if len(found) > allowed:
            for no, snippet in found[allowed:] if allowed else found:
                emit("error", rel, no, f"[{rid}] {messages[rid]}  ->  {snippet}")
                failures += 1
        elif len(found) < allowed:
            emit("error", rel, 1, f"[{rid}] baseline lists {allowed} hits but {len(found)} remain; run scripts/hub_rules.py --update-baseline")
            failures += 1
    for path, line, msg in manifest_problems():
        emit("error", path, line, msg)
        failures += 1
    grandfathered = sum(min(len(hits.get(k, [])), baseline.get(k, 0)) for k in baseline)
    if failures:
        print(f"\nHub rules: {failures} problem(s). See docs/hub-rules.md.")
        return 1
    print(f"Hub rules OK ({grandfathered} grandfathered hits still in the baseline)." if grandfathered
          else "Hub rules OK, baseline empty.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
