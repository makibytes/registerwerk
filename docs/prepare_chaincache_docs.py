#!/usr/bin/env python3
"""Prepare docs/chaincache/ for the MkDocs build.

When the private Chaincache submodule (docs/_chaincache) is present, (re)creates docs/chaincache/
as symlinks into it — this is what makes `git submodule update --init docs/_chaincache` followed by
a build actually work, rather than silently no-op'ing and leaving mkdocs to fail on dangling nav
entries. When it's absent, falls back to CI-only placeholder pages (see ALLOW_MISSING_CHAINCACHE_DOCS
below). Either way this script is the single source of truth for docs/chaincache/'s contents —
nothing else generates it, and it is safe to `rm -rf docs/chaincache` and rerun.
"""

import os
from pathlib import Path


DOCS = Path(__file__).resolve().parent
SOURCE = DOCS / "_chaincache" / "docs"
PUBLIC = DOCS / "chaincache"
TITLES = {
    "index.md": "Chaincache documentation",
    "user.md": "User guide",
    "operator.md": "Operator guide",
    "admin.md": "Administrator guide",
    "developer.md": "Developer guide",
}


def placeholder(title):
    return f"""# {title}

> **Chaincache documentation is unavailable in this build.**
>
> Registerwerk uses a pinned Chaincache submodule as the canonical source. Initialize it with
> `git submodule update --init docs/_chaincache`, then rebuild the documentation image.

Chaincache is an optional Registerwerk component. This placeholder is emitted only for CI builds
that explicitly allow the private submodule to be absent.
"""


missing = [name for name in TITLES if not (SOURCE / name).is_file()]
allow_missing = os.environ.get("ALLOW_MISSING_CHAINCACHE_DOCS", "").lower() in {
    "1",
    "true",
    "yes",
}

if missing and not allow_missing:
    names = ", ".join(missing)
    raise SystemExit(
        "Chaincache documentation submodule is not initialized "
        f"(missing: {names}). Run `git submodule update --init docs/_chaincache`."
    )

PUBLIC.mkdir(parents=True, exist_ok=True)
for name, title in TITLES.items():
    target = PUBLIC / name
    if target.exists() or target.is_symlink():
        target.unlink()
    if name in missing:
        target.write_text(placeholder(title), encoding="utf-8")
    else:
        # Relative symlink (docs/chaincache/x.md -> ../_chaincache/docs/x.md), not a copy: the
        # submodule is the canonical source, and a symlink can never silently drift stale the way
        # a copy would across a submodule bump that this script isn't rerun after.
        target.symlink_to(Path("..") / "_chaincache" / "docs" / name)
