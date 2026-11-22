#!/usr/bin/env python3
"""Keep the Android port in step with the iOS app, one sync at a time.

The parity docs (docs/parity/*.md) list every iOS source file as it was at the synced iOS
commit recorded in docs/parity/sync.json. A sync moves that commit forward:

  status  [--to REV]   what iOS changed since the synced commit, per parity file
  plan    [--to REV]   write docs/parity/sync/<from>-<to>.md: each changed iOS file with its diff
                       size, the iOS commits that touched it, and the Kotlin it maps to
  stale   [--to REV]   mark the parity rows and record REV as the sync's target:
                         changed iOS file -> Lines updated, Status `sync: <old status>`
                         new iOS file     -> new row, Status `todo`
                         deleted iOS file -> Status `removed in <sha>: <old status>`
  diff    PATH...      the iOS commits and diff for one file over the sync range
  check   [--at REV]   the parity rows against iOS at REV (default: the target, else the synced
                       commit): every source file has a row, line counts match, and the rows
                       still waiting on the sync
  advance              once `check` passes at the target with nothing waiting, make the target
                       the synced commit

The iOS checkout is $OPENCODE_IOS, else /Users/vogel/dev/opencode-ios, else ../opencode-ios.
It is only read (`git show`, `git diff`, `git log`); fetch it yourself before `plan`.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PARITY = ROOT / "docs" / "parity"
SYNC_JSON = PARITY / "sync.json"
SOURCE_ROOTS = ("OpenCode/", "OpenCodeShare/", "OpenCodeWidgets/", "OpenCodeNotifications/")
SOURCE_EXT = (".swift", ".metal")
TESTS_ROOT = "OpenCodeTests/"

# Which parity file owns a new iOS file, by folder (first match wins).
OWNERS = [
    ("OpenCode/App/", "app.md"),
    ("OpenCode/Core/Theme/", "core-ui.md"),
    ("OpenCode/Core/Motion/", "core-ui.md"),
    ("OpenCode/Core/RollingNumber/", "core-ui.md"),
    ("OpenCode/Core/", "core.md"),
    ("OpenCode/Server/", "server.md"),
    ("OpenCode/Session/", "session.md"),
    ("OpenCode/Transcript/", "transcript.md"),
    ("OpenCode/Composer/", "composer.md"),
    ("OpenCode/Drawer/", "drawer.md"),
    ("OpenCode/Settings/", "settings.md"),
    ("OpenCode/Voice/", "voice.md"),
    ("OpenCode/Media/", "media.md"),
    ("OpenCode/Browser/", "media.md"),
    ("OpenCode/System/", "system.md"),
    ("OpenCodeShare/", "system.md"),
    ("OpenCodeWidgets/", "system.md"),
    ("OpenCodeNotifications/", "system.md"),
    ("OpenCode/Tuning/", "devtools.md"),
    ("OpenCode/Diagnostics/", "devtools.md"),
    ("OpenCode/", "app.md"),
]
PENDING = ("sync", "removed")


def die(msg: str) -> None:
    print(f"ios-sync: {msg}", file=sys.stderr)
    sys.exit(1)


# --- iOS checkout -------------------------------------------------------------------------------


def ios_dir() -> Path:
    for candidate in (os.environ.get("OPENCODE_IOS"), "/Users/vogel/dev/opencode-ios", str(ROOT.parent / "opencode-ios")):
        if candidate and (Path(candidate) / ".git").exists():
            return Path(candidate)
    die("no iOS checkout: set OPENCODE_IOS to a clone of anomalyco/opencode-ios")
    raise AssertionError


def git(*args: str) -> str:
    return subprocess.run(["git", "-C", str(ios_dir()), *args], check=True, capture_output=True, text=True).stdout


def rev(name: str) -> str:
    return git("rev-parse", "--verify", f"{name}^{{commit}}").strip()


def short(sha: str) -> str:
    return sha[:8]


def is_source(path: str) -> bool:
    return path.startswith(SOURCE_ROOTS) and path.endswith(SOURCE_EXT)


def is_test(path: str) -> bool:
    return path.startswith(TESTS_ROOT) and path.endswith(".swift")


def tree(commit: str) -> list[str]:
    return git("ls-tree", "-r", "--name-only", commit).splitlines()


_line_cache: dict[tuple[str, str], int] = {}


def line_count(commit: str, path: str) -> int:
    key = (commit, path)
    if key not in _line_cache:
        blob = subprocess.run(["git", "-C", str(ios_dir()), "show", f"{commit}:{path}"], check=True, capture_output=True).stdout
        _line_cache[key] = blob.count(b"\n")
    return _line_cache[key]


@dataclass
class Change:
    status: str  # A, M, D, R
    path: str  # the path at the newer commit (the old path for D)
    old: str | None = None  # the old path for R
    added: int = 0
    removed: int = 0


def changes(base: str, head: str) -> list[Change]:
    out: dict[str, Change] = {}
    for line in git("diff", "--name-status", "-M", base, head).splitlines():
        parts = line.split("\t")
        kind = parts[0][0]
        if kind == "R":
            out[parts[2]] = Change("R", parts[2], old=parts[1])
        else:
            out[parts[1]] = Change(kind, parts[1])
    for line in git("diff", "--numstat", "-M", base, head).splitlines():
        added, removed, path = line.split("\t", 2)
        if " => " in path:  # rename: {a => b}/c or a => b
            m = re.match(r"(.*)\{(.*) => (.*)\}(.*)", path)
            path = (m.group(1) + m.group(3) + m.group(4)).replace("//", "/") if m else path.split(" => ")[1]
        if path in out and added != "-":
            out[path].added, out[path].removed = int(added), int(removed)
    return list(out.values())


def commits_touching(base: str, head: str, *paths: str) -> list[str]:
    return git("log", "--no-merges", "--format=%h %s", f"{base}..{head}", "--", *paths).splitlines()


# --- sync.json ----------------------------------------------------------------------------------


def load_sync() -> dict:
    return json.loads(SYNC_JSON.read_text())


def save_sync(data: dict) -> None:
    SYNC_JSON.write_text(json.dumps(data, indent=2) + "\n")


# --- parity docs --------------------------------------------------------------------------------


def split_cells(line: str) -> list[str]:
    """Split a Markdown table row on `|`, ignoring pipes inside backticks or escaped as `\\|`."""
    cells, cur, in_code, i = [], [], False, 0
    body = line.strip()
    if body.startswith("|"):
        body = body[1:]
    if body.endswith("|") and not body.endswith("\\|"):
        body = body[:-1]
    while i < len(body):
        ch = body[i]
        if ch == "\\" and i + 1 < len(body) and body[i + 1] == "|":
            cur.append("\\|")
            i += 2
            continue
        if ch == "`":
            in_code = not in_code
        if ch == "|" and not in_code:
            cells.append("".join(cur))
            cur = []
        else:
            cur.append(ch)
        i += 1
    cells.append("".join(cur))
    return cells


def join_cells(cells: list[str]) -> str:
    return "| " + " | ".join(c.strip() for c in cells) + " |"


@dataclass
class Row:
    doc: str
    index: int  # line index in the doc
    path: str  # full iOS path
    cell0: str  # first cell as written
    cells: list[str]
    lines_col: int | None
    status_col: int | None
    table: int  # id of the table the row sits in
    section: str

    @property
    def status(self) -> str:
        return self.cells[self.status_col].strip() if self.status_col is not None else ""

    @property
    def lines(self) -> int | None:
        if self.lines_col is None:
            return None
        text = self.cells[self.lines_col].strip().replace(",", "")
        return int(text) if text.isdigit() else None


@dataclass
class TestRow:
    doc: str
    name: str  # iOS test file name, e.g. HomeFeatureTests.swift


PATH_CELL = re.compile(r"^\s*`([^`]+)`")


def doc_path(doc: str, section: str, written: str) -> str | None:
    if doc == "server.md":
        if section.startswith("OpenCode/Server") and not written.startswith(SOURCE_ROOTS):
            return "OpenCode/Server/" + written
        if written.startswith("Core/"):
            return "OpenCode/" + written
    if written.startswith(SOURCE_ROOTS):
        return written
    return None


def written_path(doc: str, path: str) -> str:
    if doc == "server.md" and path.startswith("OpenCode/Server/"):
        return path[len("OpenCode/Server/"):]
    if doc == "server.md" and path.startswith("OpenCode/Core/"):
        return path[len("OpenCode/"):]
    return path


def parse_doc(doc: str) -> tuple[list[str], list[Row], list[TestRow]]:
    lines = (PARITY / doc).read_text().split("\n")
    rows: list[Row] = []
    tests: list[TestRow] = []
    section, header, table, in_table = "", None, 0, False
    for i, line in enumerate(lines):
        if line.startswith("## "):
            section = line[3:].strip()
        if not line.startswith("|"):
            in_table = False
            header = None
            continue
        cells = split_cells(line)
        if not in_table:
            in_table, table, header = True, table + 1, [c.strip().lower() for c in cells]
            continue
        if set("".join(cells).strip()) <= set("-: "):
            continue
        m = PATH_CELL.match(cells[0])
        if not m:
            continue
        written = m.group(1)
        if section.lower().startswith("tests"):
            if written.endswith(".swift"):
                tests.append(TestRow(doc, written.split("/")[-1]))
            continue
        path = doc_path(doc, section, written)
        if path is None:
            continue
        lines_col = header.index("lines") if header and "lines" in header else None
        status_col = header.index("status") if header and "status" in header else None
        rows.append(Row(doc, i, path, cells[0], cells, lines_col, status_col, table, section))
    return lines, rows, tests


def docs() -> list[str]:
    return sorted(p.name for p in PARITY.glob("*.md") if p.name != "README.md")


def all_rows() -> tuple[list[Row], list[TestRow]]:
    rows, tests = [], []
    for doc in docs():
        _, r, t = parse_doc(doc)
        rows += r
        tests += t
    return rows, tests


def owner(path: str) -> str:
    for prefix, doc in OWNERS:
        if path.startswith(prefix):
            return doc
    die(f"no parity file owns {path}")
    raise AssertionError


def guess_test_owner(base: str, head: str, test_path: str) -> str:
    """The parity file whose sources changed most often in the commits that touched a test."""
    votes: Counter[str] = Counter()
    for line in git("log", "--no-merges", "--format=%H", f"{base}..{head}", "--", test_path).splitlines():
        for path in git("show", "--name-only", "--format=", line).splitlines():
            if is_source(path):
                votes[owner(path)] += 1
    return votes.most_common(1)[0][0] if votes else "?"


# --- commands -----------------------------------------------------------------------------------


def sync_range(args) -> tuple[str, str]:
    data = load_sync()
    base = rev(data["synced"])
    head = rev(args.to or data.get("target") or f"origin/{data.get('branch', 'main')}")
    return base, head


def cmd_status(args) -> None:
    base, head = sync_range(args)
    rows, _ = all_rows()
    by_path = defaultdict(list)
    for r in rows:
        by_path[r.path].append(r)
    count = int(git("rev-list", "--count", "--no-merges", f"{base}..{head}"))
    print(f"iOS {short(base)}..{short(head)}: {count} commits")
    per_doc: dict[str, Counter] = defaultdict(Counter)
    tests = Counter()
    for c in changes(base, head):
        if is_source(c.path) or (c.old and is_source(c.old)):
            per_doc[owner(c.path)][c.status] += 1
            per_doc[owner(c.path)]["lines"] += c.added + c.removed
        elif is_test(c.path):
            tests[c.status] += 1
    print(f"{'parity file':16} {'changed':>8} {'new':>5} {'deleted':>8} {'renamed':>8} {'+/- lines':>10}")
    for doc in sorted(per_doc):
        n = per_doc[doc]
        print(f"{doc:16} {n['M']:>8} {n['A']:>5} {n['D']:>8} {n['R']:>8} {n['lines']:>10}")
    print(f"tests: {tests['M']} changed, {tests['A']} new, {tests['D']} deleted, {tests['R']} renamed")
    waiting = Counter(r.status.split(":")[0].split(" ")[0] for r in rows if r.status.startswith(PENDING + ("todo",)))
    if waiting:
        print("rows waiting: " + ", ".join(f"{k} {v}" for k, v in sorted(waiting.items())))


def cmd_plan(args) -> None:
    base, head = sync_range(args)
    rows, test_rows = all_rows()
    by_path = defaultdict(list)
    for r in rows:
        by_path[r.path].append(r)
    test_docs = defaultdict(set)
    for t in test_rows:
        test_docs[t.name].add(t.doc)
    count = int(git("rev-list", "--count", "--no-merges", f"{base}..{head}"))
    sections: dict[str, dict[str, list[str]]] = defaultdict(lambda: defaultdict(list))
    other: Counter[str] = Counter()

    def commit_list(*paths: str) -> str:
        subjects = commits_touching(base, head, *paths)
        shown = "; ".join(s[:110] for s in subjects[:8])
        return shown + (f"; +{len(subjects) - 8} more" if len(subjects) > 8 else "")

    for c in sorted(changes(base, head), key=lambda c: c.path):
        if is_source(c.path) or (c.old and is_source(c.old)):
            doc = owner(c.path)
            kotlin = "; ".join(sorted({r.cells[2 if r.lines_col else 1].strip() for r in by_path.get(c.old or c.path, [])}))
            size = f"+{c.added}/−{c.removed}"
            if c.status == "A":
                sections[doc]["New"].append(f"- [ ] `{c.path}` ({line_count(head, c.path)} lines). {commit_list(c.path)}")
            elif c.status == "D":
                sections[doc]["Deleted"].append(f"- [ ] `{c.path}` → delete or re-home {kotlin or '(no row)'}. {commit_list(c.path)}")
            elif c.status == "R":
                sections[doc]["Renamed"].append(f"- [ ] `{c.old}` → `{c.path}` {size} → {kotlin or '(no row)'}. {commit_list(c.old, c.path)}")
            else:
                sections[doc]["Changed"].append(f"- [ ] `{c.path}` {size} → {kotlin or '(no row)'}. {commit_list(c.path)}")
        elif is_test(c.path) or (c.old and is_test(c.old)):
            name = c.path.split("/")[-1]
            owners = sorted(test_docs.get((c.old or c.path).split("/")[-1], set()))
            if not owners:
                owners = [guess_test_owner(base, head, c.path)]
                where = f"no Tests row yet; guessed {owners[0]}"
            else:
                where = "Tests rows in " + ", ".join(owners)
            size = f"+{c.added}/−{c.removed}"
            for doc in owners:
                sections[doc]["Tests"].append(f"- [ ] {c.status} `{name}` {size} ({where}). {commit_list(c.path)}")
        else:
            top = "/".join(c.path.split("/")[:2]) if "/" in c.path else c.path
            other[top] += 1

    out = [
        f"# iOS sync {short(base)} → {short(head)}",
        "",
        f"anomalyco/opencode-ios `{base}` → `{head}`: {count} commits. Generated by",
        "`scripts/ios-sync.py plan`; tick items as they land. Read each file's diff with",
        f"`scripts/ios-sync.py diff <ios path>` (or `git -C $OPENCODE_IOS diff {short(base)} {short(head)} -- <path>`).",
        "",
    ]
    for doc in sorted(sections):
        total = sum(len(v) for v in sections[doc].values())
        out += [f"## {doc} ({total})", ""]
        for kind in ("Changed", "Renamed", "New", "Deleted", "Tests"):
            items = sections[doc].get(kind)
            if items:
                out += [f"### {kind} ({len(items)})", "", *items, ""]
    out += ["## Other iOS paths changed (review for product rules, assets, fixtures)", ""]
    out += [f"- `{k}`: {v} files" for k, v in sorted(other.items())]
    target = PARITY / "sync" / f"{short(base)}-{short(head)}.md"
    target.parent.mkdir(exist_ok=True)
    target.write_text("\n".join(out) + "\n")
    print(target.relative_to(ROOT))


def cmd_stale(args) -> None:
    base, head = sync_range(args)
    data = load_sync()
    if data.get("target") and rev(data["target"]) != head:
        die(f"a sync to {short(rev(data['target']))} is in progress; finish it or edit {SYNC_JSON.name}")
    marked = added = removed = 0
    for c in changes(base, head):
        if not (is_source(c.path) or (c.old and is_source(c.old))):
            continue
        rows, _ = all_rows()
        hits = [r for r in rows if r.path == (c.old or c.path)]
        if c.status in "MR" and hits:
            for r in hits:
                set_row(r, c.path if c.status == "R" else None, line_count(head, c.path), mark="sync")
                marked += 1
        elif c.status == "D":
            sha = commits_touching(base, head, c.path)[0].split(" ")[0]
            for r in hits:
                set_row(r, None, None, mark=f"removed in {sha}")
                removed += 1
        elif not hits:  # A, or M/R of a file with no row
            insert_row(c.path, line_count(head, c.path))
            added += 1
    data["target"] = head
    save_sync(data)
    print(f"target {short(head)}: {marked} rows marked sync, {added} rows added (todo), {removed} rows marked removed")


def set_row(r: Row, new_path: str | None, lines: int | None, mark: str) -> None:
    doc_lines = (PARITY / r.doc).read_text().split("\n")
    cells = list(r.cells)
    if new_path:
        cells[0] = cells[0].replace(f"`{written_path(r.doc, r.path)}`", f"`{written_path(r.doc, new_path)}`", 1)
    if lines is not None and r.lines_col is not None:
        cells[r.lines_col] = str(lines)
    if r.status_col is not None:
        status = cells[r.status_col].strip()
        if not status.startswith(PENDING + ("todo",)):
            cells[r.status_col] = f"{mark}: {status}"
    doc_lines[r.index] = join_cells(cells)
    (PARITY / r.doc).write_text("\n".join(doc_lines))


def insert_row(path: str, lines: int) -> None:
    doc = owner(path)
    doc_lines, rows, _ = parse_doc(doc)
    if not rows:
        die(f"{doc} has no table to add {path} to")
    folder = path.rsplit("/", 1)[0] + "/"

    def shared(r: Row) -> int:
        return len(os.path.commonprefix([r.path, folder]))

    best = max(shared(r) for r in rows)
    table = next(r.table for r in rows if shared(r) == best)
    in_table = [r for r in rows if r.table == table]
    template = in_table[0]
    cells = ["" for _ in template.cells]
    cells[0] = f"`{written_path(doc, path)}`"
    if template.lines_col is not None:
        cells[template.lines_col] = str(lines)
    if template.status_col is not None:
        cells[template.status_col] = "todo"
    before = [r for r in in_table if r.path.casefold() < path.casefold()]
    at = before[-1].index + 1 if before else in_table[0].index
    doc_lines.insert(at, join_cells(cells))
    (PARITY / doc).write_text("\n".join(doc_lines))


def cmd_diff(args) -> None:
    base, head = sync_range(args)
    for path in args.paths:
        print(f"## {path}: iOS commits {short(base)}..{short(head)}")
        print("\n".join(commits_touching(base, head, path)) or "(none)")
        print()
        sys.stdout.write(git("diff", "-M", base, head, "--", path))


def check(at: str) -> tuple[list[str], list[str], Counter]:
    rows, test_rows = all_rows()
    files = tree(at)
    sources = {p for p in files if is_source(p)}
    tests = {p.split("/")[-1] for p in files if is_test(p)}
    errors, warnings = [], []
    live = [r for r in rows if not r.status.startswith("removed")]
    covered = {r.path for r in live}
    for p in sorted(sources - covered):
        errors.append(f"no row: {p}")
    for r in live:
        if "not on iOS main" in r.cell0:  # shipped on Android first, from an iOS branch
            continue
        if r.path.endswith(SOURCE_EXT) and r.path not in sources:
            errors.append(f"{r.doc}: row for a file iOS doesn't have at {short(at)}: {r.path}")
        elif r.lines is not None and r.path in sources and r.lines != line_count(at, r.path):
            errors.append(f"{r.doc}: {r.path} has {line_count(at, r.path)} lines, row says {r.lines}")
    for r in rows:
        if r.status.startswith("removed") and r.path in sources:
            errors.append(f"{r.doc}: {r.path} is marked removed but iOS still has it at {short(at)}")
    listed = {t.name for t in test_rows}
    for name in sorted(tests - listed):
        warnings.append(f"no Tests row: {name}")
    for name in sorted(listed - tests):
        warnings.append(f"Tests row for a file iOS doesn't have at {short(at)}: {name}")
    waiting = Counter(r.status.split(":")[0].split(" ")[0] for r in rows if r.status.startswith(PENDING + ("todo",)))
    return errors, warnings, waiting


def cmd_check(args) -> None:
    data = load_sync()
    at = rev(args.at or data.get("target") or data["synced"])
    errors, warnings, waiting = check(at)
    for e in errors:
        print("error: " + e)
    for w in warnings:
        print("warning: " + w)
    rows, _ = all_rows()
    print(f"checked {len(rows)} rows against iOS {short(at)}: {len(errors)} errors, {len(warnings)} warnings"
          + ("; waiting: " + ", ".join(f"{k} {v}" for k, v in sorted(waiting.items())) if waiting else ""))
    sys.exit(1 if errors else 0)


def cmd_advance(args) -> None:
    data = load_sync()
    if not data.get("target"):
        die("no sync in progress (run `stale` first)")
    at = rev(data["target"])
    errors, _, waiting = check(at)
    pending = {k: v for k, v in waiting.items() if k in PENDING}
    if errors or pending:
        die(f"not done: {len(errors)} errors, waiting {pending}; see `check`")
    data["synced"], data["target"] = at, None
    save_sync(data)
    print(f"synced to iOS {short(at)}")


def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="cmd", required=True)
    for name in ("status", "plan", "stale"):
        s = sub.add_parser(name)
        s.add_argument("--to", help="iOS commit to sync to (default: the target, else origin/main)")
    d = sub.add_parser("diff")
    d.add_argument("paths", nargs="+")
    d.add_argument("--to")
    c = sub.add_parser("check")
    c.add_argument("--at", help="iOS commit to check against")
    sub.add_parser("advance")
    args = p.parse_args()
    {"status": cmd_status, "plan": cmd_plan, "stale": cmd_stale, "diff": cmd_diff, "check": cmd_check, "advance": cmd_advance}[args.cmd](args)


if __name__ == "__main__":
    main()
