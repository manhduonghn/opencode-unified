#!/usr/bin/env python3
"""Bake tuned values into Kotlin sources (Android counterpart of iOS `bun scripts/tune.ts bake`).

Paste the Tuning Panel's Copy Values block (`# Tuning · … ` then `key = value` lines):

    pbpaste | python3 scripts/tune.py bake          # rewrite the `default =` literals
    pbpaste | python3 scripts/tune.py bake --dry-run

It rewrites one-line declarations of the form
    Tunable("drawer.card.omega", "Card spring ω", default = 28.5, ...)
    TunableSpring("motion.morphOpen", "Morph open", duration = 0.42, bounce = 0.12)
keeping each literal's type (`3.dp`, `0.18f`, `0.2`, `6`, `true`, `Enum.Case`).
"""
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
TUNABLE = re.compile(r'Tunable\("(?P<key>[^"]+)",\s*"(?:[^"\\]|\\.)*",\s*default\s*=\s*(?P<lit>[^,)]+)')
SPRING = re.compile(r'TunableSpring\("(?P<key>[^"]+)",\s*"(?:[^"\\]|\\.)*",\s*duration\s*=\s*(?P<d>[^,)]+),\s*bounce\s*=\s*(?P<b>[^,)]+)\)')


def literal(old: str, value: str) -> str:
    old = old.strip()
    if old in ("true", "false"):
        return value
    if re.fullmatch(r"[A-Za-z_][\w]*\.[A-Za-z_]\w*", old) and not old.endswith(".dp"):
        enum = old.split(".")[0]
        return f"{enum}.{value[:1].upper()}{value[1:]}"
    number = float(value)
    text = f"{number:.4f}".rstrip("0").rstrip(".")
    if old.endswith(".dp"):
        return (text if "." in text else text + ".0") + ".dp"
    if old.endswith("f"):
        return (text if "." in text else text + ".0") + "f"
    if "." in old:
        return text if "." in text else text + ".0"
    return str(int(round(number)))


def parse(lines):
    values = {}
    for line in lines:
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = (part.strip() for part in line.split("=", 1))
        values[key] = value
    return values


def bake(values, dry_run):
    remaining = dict(values)
    for path in ROOT.glob("**/src/main/kotlin/**/*.kt"):
        text = path.read_text()
        changed = text

        def tunable(match):
            key = match.group("key")
            if key not in values:
                return match.group(0)
            remaining.pop(key, None)
            start, end = match.span("lit")
            whole = match.group(0)
            offset = match.start()
            return whole[: start - offset] + literal(match.group("lit"), values[key]) + whole[end - offset :]

        def spring(match):
            key = match.group("key")
            whole = match.group(0)
            for part, name in (("d", "duration"), ("b", "bounce")):
                full = f"{key}.{name}"
                if full in values:
                    remaining.pop(full, None)
                    whole = re.sub(rf"{name}\s*=\s*[^,)]+", f"{name} = {literal(match.group(part), values[full])}", whole, count=1)
            return whole

        changed = TUNABLE.sub(tunable, changed)
        changed = SPRING.sub(spring, changed)
        if changed != text:
            print(f"{'would update' if dry_run else 'updated'} {path.relative_to(ROOT)}")
            if not dry_run:
                path.write_text(changed)
    for key in remaining:
        print(f"not found: {key}", file=sys.stderr)
    return 1 if remaining else 0


def main():
    args = sys.argv[1:]
    if not args or args[0] != "bake":
        print(__doc__)
        return 2
    dry_run = "--dry-run" in args
    files = [a for a in args[1:] if a != "--dry-run"]
    lines = pathlib.Path(files[0]).read_text().splitlines() if files else sys.stdin.read().splitlines()
    return bake(parse(lines), dry_run)


if __name__ == "__main__":
    sys.exit(main())
