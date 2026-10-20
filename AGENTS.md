# Prototype addon API changes

Consult `.agents/skills/prototype-addon-development/SKILL.md` when changing the public addon API or integrating an addon. Whenever the public API changes, update that skill in the same development task so its signatures, examples and limitations match the implementation. This repository holds the authoritative skill; addon repositories should consume its current copy rather than maintain independent stale copies.

Keep class gameplay and progression in addons. Keep client-only Minecraft classes out of common/server APIs. Preserve unrelated work. Validate API changes with unit tests, dedicated-server GameTests and a clean production build.
