# CLAUDE.md

Short reference for Claude (or any AI agent) on how this repo's release flow
works. Read on session start so amnesic sessions don't push to the wrong branch.

## Project

LofiPod — Android podcast app, sideloaded, GitHub Actions release pipeline.
Single user.

## Branch model

- **`dev`** — working branch. All commits go here.
- **`main`** — updated **only on minor-version bumps** (e.g. 0.3.x → 0.4.0).
  Represents the stable release line.

**Default rule: commit + push to `dev`. Do NOT tag.** Ongoing commits on
`dev` are just changes to the app — hardly distinct versions, not releases.
Keep development going untagged until the user decides a build is ready for
the device.

**Tag ONLY when the user says "ship it."** That phrase is the explicit signal
that the current build is intended to hit the device: cut the next patch tag
`v<next-patch>` and push it (see below). The in-app updater only sees tagged
GitHub Releases, so the tag is what actually delivers the build to the phone.
Without "ship it," leave it on `dev`. Don't touch `main` unless the user
explicitly says "minor bump"; `main` only catches up at minor-bump time via a
deliberate merge.

## Shipping a patch release

```bash
# On dev:
git push origin dev
git tag v0.3.5            # next patch number
git push origin v0.3.5
```

Tag push triggers `.github/workflows/release.yml`, which builds a signed APK,
generates `latest.json`, and creates a GitHub Release. The in-app updater
(Settings → Updates) picks it up at the next 23:59 nightly check, or on demand
via the "Check now" button. Releases are the visible delivery surface; commits
on `dev` are the source. Tagging happens on "ship it," not automatically.

## Versioning

- Tag format: `v<semver>`, leading `v` required (workflow filter).
- `versionName` = tag with the `v` stripped (e.g. tag `v0.3.5` → name `0.3.5`).
- `versionCode` auto-derived from `github.run_number` (release.yml). Always
  monotonic; the user shouldn't bump it manually.
- App displays `versionName` in Settings → Updates ("Installed: v0.3.5").

## Don't touch

- `app/lofipod-dev.jks` — signing keystore.
- `app/src/main/assets/kabod/*.kabod` — bundled content packs.
- `.github/workflows/release.yml` — handle with care; it's the live pipeline.
- `LofiPod Design/` and `tools/_*` — gitignored, personal scratch.
