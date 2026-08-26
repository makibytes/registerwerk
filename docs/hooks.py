import os
from pathlib import Path

from mkdocs.exceptions import PluginError


CHAINCACHE_SOURCE = Path(__file__).resolve().parent / "_chaincache" / "docs"
CHAINCACHE_PAGES = ("index.md", "user.md", "operator.md", "admin.md", "developer.md")


def on_config(config, **kwargs):
    """Fail early when a normal build lacks the canonical Chaincache documentation."""
    missing = [name for name in CHAINCACHE_PAGES if not (CHAINCACHE_SOURCE / name).is_file()]
    allow_missing = os.environ.get("ALLOW_MISSING_CHAINCACHE_DOCS", "").lower() in {
        "1",
        "true",
        "yes",
    }

    if missing and not allow_missing:
        names = ", ".join(missing)
        raise PluginError(
            "Chaincache documentation submodule is not initialized "
            f"(missing: {names}). Run `git submodule update --init docs/_chaincache`."
        )
    return config


def on_page_markdown(markdown, **kwargs):
    backend_url = os.environ.get("BACKEND_URL", "http://localhost:48080").rstrip("/")
    return markdown.replace("{{ backend_url }}", backend_url)
