# Icon design philosophy

LofiPod's design convention for icons, established v0.10.3 (2026-05-13).

## The current state (mixed)

The app currently uses two icon sources:

1. **`androidx.compose.material:material-icons-extended`** — the bulk of
   existing UI icons (ArrowBack, Pause, PlayArrow, Speed, MoreVert,
   etc.). Drawn from the **older Material Icons** set, which Google has
   officially deprecated and stopped publishing updates to.
2. **Vendored Material Symbols vector drawables in `res/drawable/`** —
   currently just `valve_24.xml` (the manual flush plunger button).

Why mixed: when LofiPod started, material-icons-extended was the
standard. It's a deprecated dependency now, but migrating the
~50 existing icon references in one push would be churn without user
benefit. The agreed-on policy is: **don't bulk-migrate, but stop adding
new icons under the old set.**

## Convention for new icons (v0.10.3+)

**Source.** Material Symbols from [fonts.google.com/icons](https://fonts.google.com/icons)
(Google's current and active icon set, replacing Material Icons).
Source repo: [google/material-design-icons](https://github.com/google/material-design-icons),
license: **Apache-2.0** — fully usable in commercial / closed-source /
sideloaded apps with attribution. No royalties, no copyleft, no
distribution restrictions.

**Style.** Outlined. The `valve_24` we shipped is outlined; future icons
should match for visual consistency. (Material Symbols offers Outlined,
Rounded, and Sharp variants; we standardize on Outlined.)

**Form.** Vector drawables in `app/src/main/res/drawable/`. Naming:
`<snake_case_name>_24.xml` (e.g., `valve_24.xml`, `arrow_back_24.xml`,
`graphic_eq_24.xml`). The `_24` suffix denotes 24dp default size, matching
Google's naming convention from the Material Symbols Android downloads.

**Compose usage.**
```kotlin
import androidx.compose.ui.res.painterResource
import com.lofipod.app.R

Icon(
    painter = painterResource(id = R.drawable.<name>_24),
    contentDescription = "..."
)
```
The `Icon` composable applies its own `LocalContentColor` tint over the
vector's white fill, so theme color follows automatically.

## When to add a new icon as Material Symbols

- The icon isn't in `material-icons-extended` (e.g., Valve, newer
  glyphs).
- The icon IS in `material-icons-extended` but we want the Material
  Symbols visual style (rounded corners, slightly different weight).
- We're rebuilding a UI surface for other reasons and the existing
  icons there are due for a refresh.

## When NOT to migrate (yet)

- Existing icons that work fine and aren't being touched. Don't bulk
  rewrite `Icons.Filled.ArrowBack` -> `arrow_back_24.xml` across 50
  files for the sake of consistency alone. The visual style difference
  is subtle; the maintenance cost is real.
- During an unrelated UI change (e.g., refactoring the player screen
  layout). Stay focused.

## Getting the XML for a new icon

1. Visit [fonts.google.com/icons](https://fonts.google.com/icons) and
   search for the icon name.
2. Choose **Outlined** style (consistency with `valve_24`).
3. Click the **Android** tab and download the XML.
4. Or fetch directly from the source repo:
   ```
   https://raw.githubusercontent.com/google/material-design-icons/master/symbols/android/<icon_name>/materialsymbolsoutlined/<icon_name>_24px.xml
   ```
5. Save as `app/src/main/res/drawable/<icon_name>_24.xml`.
6. Edit the file:
   - Remove any `android:tint="?attr/colorControlNormal"` attribute.
     The Compose `Icon` composable applies its own tint.
   - Ensure `android:fillColor="#FFFFFFFF"` (white). Compose's tint
     overrides it at render time.

## License attribution

All Material Symbols icons we vendor are Apache-2.0 licensed by Google.
The full license text is bundled at
`app/src/main/assets/licenses/LICENSE-MATERIAL-SYMBOLS.txt` (v0.10.3+).
The About section in Settings credits the source.

## Eventual full migration

When R8 minification is eventually re-enabled (currently OFF since
v0.3.0 due to silent-audio reflection issue with Media3 — a fix would
require authoring proper keep-rules), the unused icons in
material-icons-extended will be tree-shaken from the APK and the
deprecated-dependency footprint will be minimal. At that point a full
migration becomes lower priority. Until then: gradual transition,
documented here.
