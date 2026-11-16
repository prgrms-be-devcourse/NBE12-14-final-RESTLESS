"""Read scale.yml and print shell assignments (SCALE_<key>=<int>) for one scale.

Only the subset used by scale.yml is accepted: top-level sections, two-space "key: integer" lines.
Anything else is an error so a typo never silently falls back to a default.
"""
import re
import sys

LINE = re.compile(r"^(  )?([a-z][a-z0-9_]*):\s*(-?\d+)?\s*$")


def parse(path):
    sections, current = {}, None
    for number, raw in enumerate(open(path, encoding="utf-8"), 1):
        line = raw.split("#", 1)[0].rstrip()
        if not line:
            continue
        match = LINE.match(line)
        if not match:
            sys.exit(f"scale.yml:{number}: unsupported line: {raw.rstrip()}")
        indent, key, value = match.groups()
        if not indent:
            if value is not None:
                sys.exit(f"scale.yml:{number}: top-level key needs a section body")
            current = sections.setdefault(key, {})
        elif current is None or value is None:
            sys.exit(f"scale.yml:{number}: expected 'key: integer' inside a section")
        else:
            current[key] = int(value)
    return sections


def main():
    path, scale = sys.argv[1], sys.argv[2]
    sections = parse(path)
    if scale == "common" or scale not in sections:
        names = ", ".join(k for k in sections if k != "common")
        sys.exit(f"unknown scale '{scale}'. defined: {names}")
    values = {**sections.get("common", {}), **sections[scale]}
    for key, value in sorted(values.items()):
        print(f"SCALE_{key}={value}")


if __name__ == "__main__":
    main()
