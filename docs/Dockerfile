# Documentation server: MkDocs Material plus the static-i18n plugin, which the
# stock squidfunk/mkdocs-material image does not ship.
#
# Build context is the repository root (see docker-compose.yml), so that mkdocs.yml
# and docs/ are both reachable; nothing is COPYed in — the compose service mounts
# them read-only so edits are picked up live by `mkdocs serve`.
FROM squidfunk/mkdocs-material:9.5

# Pinned: the plugin's nav_translations and Material language-switcher integration
# are version-sensitive, and an unpinned rebuild silently changing the switcher's
# behaviour is exactly the kind of drift a docs image should not have.
RUN pip install --no-cache-dir "mkdocs-static-i18n[material]==1.2.3"
