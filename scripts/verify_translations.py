#!/usr/bin/env python3
"""Require exact en-US/ja-JP GUI translation-key parity."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    lang = (args.repo_root / "forge-1.20.1" / "src" / "main" / "resources"
            / "assets" / "reality_foundation" / "lang")
    en = json.loads((lang / "en_us.json").read_text(encoding="utf-8"))
    ja = json.loads((lang / "ja_jp.json").read_text(encoding="utf-8"))
    if set(en) != set(ja):
        raise ValueError(
            f"translation key mismatch: en-only={sorted(set(en) - set(ja))}, "
            f"ja-only={sorted(set(ja) - set(en))}")
    required = {
        "foundation.gui.tab.overview", "foundation.gui.tab.health",
        "foundation.gui.health.line", "foundation.gui.health.page",
        "foundation.gui.label.connection", "foundation.gui.label.audit",
        "foundation.audit.configured", "foundation.audit.not_configured",
    }
    if not required.issubset(en):
        raise ValueError(f"required GUI translation keys missing: {sorted(required - set(en))}")
    print(f"translation parity passed: {len(en)} keys")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
