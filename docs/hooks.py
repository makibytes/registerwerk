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


# Shown on pages that have no translation in a language build. mkdocs-static-i18n serves the English
# page there (`fallback_to_default: true`); without a banner a reader of the German site could take
# English safety text for a translation that was reviewed. The English text is authoritative.
FALLBACK_BANNERS = {
    "de": ("Nur auf Englisch verfügbar", "Diese Seite ist noch nicht ins Deutsche übersetzt. Sie wird auf Englisch angezeigt; maßgeblich ist der englische Text."),
    "fr": ("Disponible en anglais uniquement", "Cette page n'est pas encore traduite en français. Elle est affichée en anglais ; le texte anglais fait foi."),
    "es": ("Disponible solo en inglés", "Esta página aún no está traducida al español. Se muestra en inglés; el texto en inglés es el de referencia."),
    "it": ("Disponibile solo in inglese", "Questa pagina non è ancora tradotta in italiano. Viene mostrata in inglese; fa fede il testo inglese."),
}
# Canonical Chaincache pages are English-only by design (docs/prepare_chaincache_docs.py).
FALLBACK_BANNER_EXCLUDED_PREFIXES = ("chaincache/",)


def _fallback_banner(markdown, page):
    file = getattr(page, "file", None)
    target = getattr(file, "locale_alternate_of", None)
    source = getattr(file, "locale", None)
    banner = FALLBACK_BANNERS.get(target)
    if banner is None or source == target:
        return markdown
    src_uri = getattr(file, "src_uri", "") or ""
    if src_uri.startswith(FALLBACK_BANNER_EXCLUDED_PREFIXES):
        return markdown
    title, text = banner
    block = f'!!! warning "{title}"\n    {text}\n'
    lines = markdown.split("\n")
    for index, line in enumerate(lines):
        if line.startswith("# "):
            lines[index + 1:index + 1] = ["", *block.rstrip("\n").split("\n")]
            return "\n".join(lines)
    return block + "\n" + markdown


def on_page_markdown(markdown, page=None, **kwargs):
    backend_url = os.environ.get("BACKEND_URL", "http://localhost:48080").rstrip("/")
    markdown = markdown.replace("{{ backend_url }}", backend_url)
    return _fallback_banner(markdown, page)
