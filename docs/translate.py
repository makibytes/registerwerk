#!/usr/bin/env python3
"""
Translate the Registerwerk documentation into de / fr / it / es.

Hand-translating ~113 pages into four languages is roughly 54,000 lines of prose.
This drives it through the Anthropic API with the project's own terminology list
(TRANSLATION-TERMINOLOGY.md) as the binding glossary, and validates that the
translated file still has the same *structure* as its source before writing it.

    export ANTHROPIC_API_KEY=sk-ant-...
    pip install anthropic
    python3 docs/translate.py                    # everything still missing
    python3 docs/translate.py --lang de           # one language
    python3 docs/translate.py --path customer     # one subtree
    python3 docs/translate.py --dry-run           # list what would be done
    python3 docs/translate.py --recheck           # re-validate existing files

Resumable: a page that already has a .<lang>.md file is skipped unless --force.
Nothing is deleted, and a translation that fails validation is reported and
skipped rather than written — a structurally broken page would break the build.
"""

from __future__ import annotations

import argparse
import concurrent.futures
import os
import re
import subprocess
import sys
import unicodedata
from pathlib import Path

DOCS = Path(__file__).resolve().parent
GLOSSARY = DOCS / "TRANSLATION-TERMINOLOGY.md"

LANGUAGES = {
    "de": "German (Deutsch)",
    "fr": "French (Français)",
    "it": "Italian (Italiano)",
    "es": "Spanish (Español)",
}

# Kept out of the build, or not prose.
EXCLUDE_DIRS = {"node_modules", "includes", "build", "src", "static", "assets", "claims"}
EXCLUDE_FILES = {"TRANSLATION-TERMINOLOGY.md"}

DEFAULT_MODEL = os.environ.get("RW_TRANSLATE_MODEL", "opus")

SYSTEM = """You are translating the documentation of Registerwerk, an electronic-securities
registry, from English into {language}.

This is regulated-finance documentation. Precision outranks fluency: a reader may rely on it
when deciding what a legal control does. Never soften, never embellish, never add or drop a
claim. If the English hedges ("intended to support", "does not prove", "advisory, not
enforcing"), the translation must hedge exactly as much.

## Terminology is binding

The glossary below is the project's authoritative term list, anchored to each language's own
national statute. Use its renderings. Where it warns about a false friend, obey the warning.

<glossary>
{glossary}
</glossary>

## What must survive translation byte-for-byte

- YAML front matter: translate ONLY the `title:` and `description:` values. Every other key,
  and all key names, stay exactly as they are.
- Fenced code blocks (```): never translate contents. This includes ```mermaid — translate only
  the human-readable label text inside quotes/brackets, never node ids, arrows or keywords.
- Inline code (`like this`): never translate.
- Link targets: `[text](target.md#anchor)` — translate the text, never the target or anchor.
- Admonition and detail markers: `!!! note`, `??? note`, `=== "Tab"`. Keep the marker and the
  type word (note/warning/danger/tip/info/example/abstract/question) in English; translate only
  the quoted title. Preserve the 4-space indentation of admonition bodies exactly.
- HTML blocks and attribute lists (`{{ .md-button }}`, `<div class="grid cards" markdown>`).
- Material icon shortcodes such as `:material-account-tie:` and `:octicons-arrow-right-24:`.
- Table pipes and alignment rows.

## What must NOT be translated

- UI labels of the portals, which are English-only software: navigation items, button labels,
  workspace names (Investor / Trader / Issuer), field names.
- Status and role constants: DRAFT, PENDING_APPROVAL, APPROVED, ISSUED, SUSPENDED, REDEEMED,
  PENDING, SETTLED, FAILED, CANCELLED, REFUNDED, REGISTRY_ADMIN, COMPANY_ADMIN, ISSUER,
  INVESTOR, TRADER, AUDIT, DAPP_PUBLISHER, and similar SCREAMING_SNAKE_CASE values.
- Configuration keys, environment variables, table and column names, class and file names.
- German statute references: §16 eWpG, §17(2) eWpG, §19(2) eWpG, §24 eWpG, §26 eWpG. These name
  provisions of German law and must stay citable. Likewise keep Sammeleintragung,
  Einzeleintragung, Sperrvermerk and Registerauszug in German, with a short gloss in the target
  language on first use.
- Standard names (ERC-3643, T-REX, ONCHAINID, SPL-2022), product names, the running example
  company "Nordwind Energie GmbH".

Where you keep an English UI label, gloss it once in parentheses so the reader knows what it
means, e.g. — Trading Desk → **Create listing** (…).

## Register and tone

Match the source's voice: direct, plain, occasionally blunt. It uses short sentences and says
uncomfortable things plainly. Do not inflate it into corporate prose.

Use the formal address customary for professional documentation in the target language
(de: Sie; fr: vous; es: usted; it: the impersonal/second person as the source uses — the source
addresses the reader directly and informally in Italian, keep "tu" consistent within a page).

Numbers: localise decimal and thousands separators and currency placement to the target
locale (de/it/es: 1.000,50 €; fr: 1 000,50 €). Dates: use the target locale's ordering.

## Output

Return ONLY the translated Markdown file. No preamble, no explanation, no code fence wrapping
the whole document. Start with the `---` of the front matter.
"""


def source_pages(subpath: str | None) -> list[Path]:
    """Every English source page, i.e. .md files that are not themselves translations."""
    out = []
    for p in sorted(DOCS.rglob("*.md")):
        rel = p.relative_to(DOCS)
        if rel.parts[0] in EXCLUDE_DIRS or p.name in EXCLUDE_FILES:
            continue
        # a translation looks like foo.de.md — two suffixes, the first a known locale
        stem_suffix = Path(p.stem).suffix.lstrip(".")
        if stem_suffix in LANGUAGES:
            continue
        if subpath and not str(rel).startswith(subpath):
            continue
        out.append(p)
    return out


def target_for(page: Path, lang: str) -> Path:
    return page.with_suffix(f".{lang}.md")


# --------------------------------------------------------------------------- validation

FENCE = re.compile(r"^```", re.M)
LINK = re.compile(r"\]\(([^)\s]+)\)")
ADMON = re.compile(r"^(\s*)(!!!|\?\?\?\+?|===)\s", re.M)
FRONT = re.compile(r"\A---\n.*?\n---\n", re.S)
HEADING = re.compile(r"^#{1,6}\s+(.+?)\s*$", re.M)
EXPLICIT_ID = re.compile(r"\{#([\w-]+)\}\s*$")


def slugify(text: str) -> str:
    """Mirror markdown.extensions.toc.slugify, which is what MkDocs uses for anchors."""
    text = re.sub(r"<[^>]+>", "", text)                       # inline HTML
    text = re.sub(r"[*_`]", "", text)                         # emphasis / code marks
    text = unicodedata.normalize("NFKD", text)
    text = text.encode("ascii", "ignore").decode("ascii")     # é → e, — dropped
    text = re.sub(r"[^\w\s-]", "", text).strip().lower()
    return re.sub(r"[-\s]+", "-", text)


def anchors_of(markdown: str) -> set[str]:
    """Every anchor a page offers: heading slugs plus explicit {#id} overrides."""
    found = set()
    body = FENCE.split(markdown)[::2]                          # skip fenced code
    for chunk in body:
        for raw in HEADING.findall(chunk):
            if explicit := EXPLICIT_ID.search(raw):
                found.add(explicit.group(1))
                raw = EXPLICIT_ID.sub("", raw)
            found.add(slugify(raw))
    return found


def split_target(target: str) -> tuple[str, str]:
    path, _, anchor = target.partition("#")
    return path, anchor


def check_anchors(out: str, page: Path, lang: str) -> list[str]:
    """
    Resolve every anchor the translation points at, against the page it actually lands on
    in this language (falling back to the English page when untranslated).

    MkDocs reports a dangling anchor only at INFO level, so --strict does not catch it.
    A cross-reference into a translated heading that was not itself updated therefore
    fails silently in the browser — exactly the bug this catches.
    """
    problems = []
    for target in LINK.findall(out):
        path, anchor = split_target(target)
        if not anchor or path.startswith(("http://", "https://", "mailto:")):
            continue

        if not path:                                   # same-page anchor
            resolved = out
        else:
            base = (page.parent / path).resolve()
            if base.suffix != ".md":
                continue
            translated = base.with_suffix(f".{lang}.md")
            source = translated if translated.exists() else base
            if not source.exists():
                continue                                # a bad path is reported separately
            resolved = source.read_text(encoding="utf-8")

        if anchor not in anchors_of(resolved):
            problems.append(f"anchor not found in target: {target}")
    return problems


def validate(src: str, out: str) -> list[str]:
    """Structural checks. Catches the failure modes that break a MkDocs build."""
    problems = []

    if not FRONT.match(out):
        problems.append("missing or malformed YAML front matter")

    if len(FENCE.findall(src)) != len(FENCE.findall(out)):
        problems.append(
            f"code-fence count changed ({len(FENCE.findall(src))} → {len(FENCE.findall(out))})"
        )

    # Link *paths* must be identical — a translated path is a broken cross-reference.
    # Link *anchors* must NOT be identical when they point into a translated page: the
    # target's headings are translated, so its anchors are too. Checking the whole target
    # string conflates the two and flags correct work as broken.
    src_paths = sorted(split_target(t)[0] for t in LINK.findall(src))
    out_paths = sorted(split_target(t)[0] for t in LINK.findall(out))
    if src_paths != out_paths:
        lost = set(src_paths) - set(out_paths)
        added = set(out_paths) - set(src_paths)
        if lost:
            problems.append(f"link paths lost/altered: {sorted(lost)[:5]}")
        if added:
            problems.append(f"unexpected link paths: {sorted(added)[:5]}")

    # Losing an admonition or tab marker breaks the page's structure. *Adding* one is
    # legitimate — translations carry translator's notes the English original has no need
    # for (e.g. "the UI stays in English"), so only a shortfall is an error.
    if len(ADMON.findall(out)) < len(ADMON.findall(src)):
        problems.append(
            f"admonition/tab markers lost "
            f"({len(ADMON.findall(src))} → {len(ADMON.findall(out))})"
        )

    if out.lstrip().startswith("```"):
        problems.append("whole document wrapped in a code fence")

    # Statute references must survive verbatim.
    for ref in re.findall(r"§\s?\d+[a-z]?(?:\(\d+\))? eWpG", src):
        if ref not in out:
            problems.append(f"statute reference dropped: {ref}")
            break

    return problems


# --------------------------------------------------------------------------- translation


def call_cli(system: str, user: str, model: str) -> str:
    """
    Drive the authenticated `claude` CLI in print mode.

    This is the default backend because it needs no separate API key — it reuses the
    Claude Code login already on the machine. Tools are disabled: the model must
    translate the text it was given, not go reading the repository.
    """
    proc = subprocess.run(
        [
            "claude", "-p",
            "--system-prompt", system,
            "--model", model,
            "--allowed-tools", "",
            "--max-turns", "1",
        ],
        input=user,
        capture_output=True,
        text=True,
        timeout=900,
    )
    if proc.returncode != 0:
        raise RuntimeError((proc.stderr or proc.stdout).strip()[:400])
    return proc.stdout


def call_api(system: str, user: str, model: str) -> str:
    import anthropic

    resp = anthropic.Anthropic().messages.create(
        model=model,
        max_tokens=32000,
        system=system,
        messages=[{"role": "user", "content": user}],
    )
    return "".join(b.text for b in resp.content if getattr(b, "type", None) == "text")


def translate_one(backend, page: Path, lang: str, force: bool, model: str) -> tuple[Path, str]:
    target = target_for(page, lang)
    rel = page.relative_to(DOCS)

    if target.exists() and not force:
        return target, "skip"

    src = page.read_text(encoding="utf-8")
    glossary = GLOSSARY.read_text(encoding="utf-8")

    try:
        raw = backend(
            SYSTEM.format(language=LANGUAGES[lang], glossary=glossary),
            f"Translate this documentation page ({rel}).\n\n{src}",
            model,
        )
    except Exception as exc:  # noqa: BLE001 — surface any failure per-page, keep going
        return target, f"error: {exc}"

    out = raw.strip()
    out = re.sub(r"\A```[a-z]*\n|\n```\Z", "", out) + "\n"

    problems = validate(src, out) + check_anchors(out, page, lang)
    if problems:
        return target, "invalid: " + "; ".join(problems)

    target.write_text(out, encoding="utf-8")
    return target, "written"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--lang", choices=sorted(LANGUAGES), action="append", help="repeatable; default all")
    ap.add_argument("--path", help="only pages under this docs-relative subpath, e.g. customer")
    ap.add_argument("--force", action="store_true", help="retranslate pages that already exist")
    ap.add_argument("--dry-run", action="store_true", help="list work without calling the API")
    ap.add_argument("--recheck", action="store_true", help="validate existing translations, translate nothing")
    ap.add_argument("--jobs", type=int, default=4, help="parallel requests (default 4)")
    ap.add_argument("--backend", choices=["cli", "api"], default="cli",
                    help="cli = the authenticated `claude` CLI (no API key needed); api = Anthropic SDK")
    ap.add_argument("--model", default=DEFAULT_MODEL, help=f"model alias (default {DEFAULT_MODEL})")
    args = ap.parse_args()

    langs = args.lang or sorted(LANGUAGES)
    pages = source_pages(args.path)

    if args.recheck:
        bad = 0
        for page in pages:
            for lang in langs:
                t = target_for(page, lang)
                if not t.exists():
                    continue
                out = t.read_text(encoding="utf-8")
                problems = validate(page.read_text(encoding="utf-8"), out) + check_anchors(out, page, lang)
                if problems:
                    bad += 1
                    print(f"FAIL {t.relative_to(DOCS)}: {'; '.join(problems)}")
        print(f"\n{bad} structural problem(s) found.")
        return 1 if bad else 0

    todo = [
        (page, lang)
        for page in pages
        for lang in langs
        if args.force or not target_for(page, lang).exists()
    ]

    print(f"{len(pages)} source pages · {len(langs)} languages · {len(todo)} files to translate")
    if args.dry_run:
        for page, lang in todo:
            print(f"  {lang}  {page.relative_to(DOCS)}")
        return 0
    if not todo:
        print("Nothing to do.")
        return 0

    if args.backend == "api":
        if not os.environ.get("ANTHROPIC_API_KEY"):
            print("--backend api needs ANTHROPIC_API_KEY", file=sys.stderr)
            return 2
        backend = call_api
    else:
        backend = call_cli

    print(f"backend: {args.backend} · model: {args.model} · jobs: {args.jobs}\n")
    written = skipped = failed = 0

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.jobs) as pool:
        futures = {pool.submit(translate_one, backend, p, l, args.force, args.model): (p, l)
                   for p, l in todo}
        for i, fut in enumerate(concurrent.futures.as_completed(futures), 1):
            target, status = fut.result()
            name = target.relative_to(DOCS)
            if status == "written":
                written += 1
                print(f"[{i}/{len(todo)}] ok      {name}")
            elif status == "skip":
                skipped += 1
            else:
                failed += 1
                print(f"[{i}/{len(todo)}] FAILED  {name}: {status}")

    print(f"\nwritten {written} · skipped {skipped} · failed {failed}")
    print("Now run a strict build before committing:")
    print("  docker compose --profile docs build docs && \\")
    print("  docker run --rm -v $PWD/mkdocs.yml:/docs/mkdocs.yml:ro -v $PWD/docs:/docs/docs:ro \\")
    print("    registerwerk-docs:local build --strict")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
