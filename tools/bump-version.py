#!/usr/bin/env python3
import argparse
import re
import sys
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(
        description="Bump APP_VERSION_CODE and APP_VERSION_NAME in gradle.properties"
    )
    parser.add_argument(
        "name",
        nargs="?",
        help="Explicit version name to set (e.g. 0.5.0)",
    )
    parser.add_argument(
        "--minor",
        action="store_true",
        help="Bump minor version (e.g. 0.4.0 -> 0.5.0)",
    )
    parser.add_argument(
        "--patch",
        action="store_true",
        help="Bump patch version (e.g. 0.4.0 -> 0.4.1)",
    )
    parser.add_argument(
        "--code-only",
        action="store_true",
        help="Only increment versionCode without changing versionName",
    )
    args = parser.parse_args()

    prop_path = Path("gradle.properties")
    if not prop_path.exists():
        print(f"Error: {prop_path} not found.", file=sys.stderr)
        sys.exit(1)

    content = prop_path.read_text(encoding="utf-8")

    # Increment versionCode
    code_match = re.search(r"APP_VERSION_CODE=(\d+)", content)
    if not code_match:
        print("Error: APP_VERSION_CODE not found in gradle.properties", file=sys.stderr)
        sys.exit(1)

    old_code = int(code_match.group(1))
    new_code = old_code + 1
    content = re.sub(r"APP_VERSION_CODE=\d+", f"APP_VERSION_CODE={new_code}", content)

    # Determine versionName
    old_name = None
    new_name = None
    name_match = re.search(r"APP_VERSION_NAME=(\S+)", content)
    if name_match:
        old_name = name_match.group(1)

    if not args.code_only and old_name:
        if args.name:
            new_name = args.name
        else:
            semver = re.match(r"^(\d+)\.(\d+)\.(\d+)(.*)$", old_name)
            if semver:
                major = int(semver.group(1))
                minor = int(semver.group(2))
                patch = int(semver.group(3))
                rest = semver.group(4)
                if args.minor:
                    new_name = f"{major}.{minor + 1}.0{rest}"
                else:
                    new_name = f"{major}.{minor}.{patch + 1}{rest}"
            else:
                new_name = old_name

        if new_name and new_name != old_name:
            content = re.sub(r"APP_VERSION_NAME=\S+", f"APP_VERSION_NAME={new_name}", content)

    prop_path.write_text(content, encoding="utf-8")

    print(f"Version bumped:")
    print(f"  APP_VERSION_CODE: {old_code} -> {new_code}")
    if new_name and new_name != old_name:
        print(f"  APP_VERSION_NAME: {old_name} -> {new_name}")
    else:
        print(f"  APP_VERSION_NAME: {old_name} (unchanged)")


if __name__ == "__main__":
    main()
