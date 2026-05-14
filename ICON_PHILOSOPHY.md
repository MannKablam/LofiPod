# Icon design philosophy

LofiPod's design convention for icons, established v0.10.3 (2026-05-13).

## Current state (v0.10.4+: fully migrated)

All icons in LofiPod ship as **Material Symbols vector drawables**
vendored in `app/src/main/res/drawable/<snake_case_name>_24.xml`.
The deprecated `androidx.compose.material:material-icons-extended`
dependency has been removed from `app/build.gradle.kts`. ~51 vector
drawables ship, only the ones we actually use.

Earlier state (v0.10.2 / v0.10.3, since-superseded): mixed mode where
material-icons-extended powered the bulk of icons and `valve_24` was
the first Material Symbol exception. v0.10.4 completed the migration.

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

## Migration history

v0.10.4 (2026-05-13): the full migration. ~51 unique icons fetched from
google/material-design-icons via PowerShell + Invoke-WebRequest into
`res/drawable/<snake_case>_24.xml`. ~23 Kotlin files updated from
`Icons.Filled.X` / `Icons.AutoMirrored.Filled.X` to
`painterResource(R.drawable.x_24)`. `android:tint="?attr/colorControlNormal"`
stripped from all drawables so Compose's `LocalContentColor` tint is
the sole source. `android:autoMirrored="true"` ensured on the four
RTL-aware drawables (arrow_back, format_list_bulleted, list,
playlist_add).

Special-cased icons:
  - `Icons.Filled.Favorite` (filled solid heart) → `favorite_24.xml`,
    sourced from `favorite_fill1_24px.xml` (filled variant of the
    Material Symbols favorite icon).
  - `Icons.Filled.FavoriteBorder` (outlined heart) →
    `favorite_border_24.xml`, sourced from `favorite_24px.xml`
    (default outlined variant).
  - `Icons.Filled.ErrorOutline` → `error_outline_24.xml`, sourced
    from Material Symbols `error_24px.xml` (outlined variant of the
    error icon).

APK size effect: the deprecated material-icons-extended library
(~25 MB pre-R8) is gone. The 51 vector drawables ship as small XML
resources (~100-300 bytes each, ~10 KB total). Net APK shrinkage
is substantial; we no longer need R8 tree-shaking just for icons.
