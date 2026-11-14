"""Reject Android production module dependencies that invert architectural ownership."""
from pathlib import Path
import re
import sys

DEPENDENCY = re.compile(r'(?<![\w])(?:implementation|api|compileOnly|runtimeOnly)\s*\(\s*project\s*\(\s*"(:[^"\s]+)"\s*\)\s*\)')
FORBIDDEN = {
    "core": ("feature", "runtime", "plugin", "app"),
    "runtime": ("feature", "app"),
    "plugin": ("feature", "app"),
    "feature": ("app",),
}


def violations(module, script):
    # Strip comments so examples cannot create dependencies; Gradle's production
    # project declarations in this repository use literal project(":name") calls.
    script = re.sub(r'/\*.*?\*/|//[^\n]*', '', script, flags=re.DOTALL)
    forbidden = FORBIDDEN.get(module.split(":")[0], ())
    return [f"{module} cannot depend on {dependency}"
            for dependency in DEPENDENCY.findall(script)
            if dependency.strip(":").split(":")[0] in forbidden]


def main():
    root = Path(__file__).resolve().parent.parent
    failures = []
    count = 0
    for group in FORBIDDEN:
        for script in sorted((root / group).glob("*/build.gradle.kts")):
            module = ":".join(script.relative_to(root).parts[:-1])
            failures.extend(violations(module, script.read_text(encoding="utf-8")))
            count += 1
    if not count:
        raise RuntimeError("No Android modules found")
    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    print(f"Module dependency directions checked: {count} modules")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
