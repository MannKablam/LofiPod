# Stam Ashkenaz CLM — license attribution

File: `stam_ashkenaz_clm.ttf`
Source: Culmus Hebrew Fonts project, version 0.133
https://culmus.sourceforge.io/
Original filename: `StamAshkenazCLM.ttf`

## Copyright

Stam Ashkenaz font is copyright 2007–2010 by Yoram Gnat
(yoram.gnat@gmail.com).

## License: GNU GPL v2 with font embedding exception

The Culmus package is distributed under the GNU General Public License
version 2.

Per the upstream LICENSE file, the following font embedding exception
applies to Stam Ashkenaz (and to most other Culmus fonts):

> As a special exception, if you create a document which uses this font,
> and embed this font or unaltered portions of this font into the
> document, this font does not by itself cause the resulting document
> to be covered by the GNU General Public License. This exception does
> not however invalidate any other reasons why the document might be
> covered by the GNU General Public License. If you modify this font,
> you may extend this exception to your version of the font, but you
> are not obligated to do so. If you do not wish to do so, delete this
> exception statement from your version.

**LofiPod ships `stam_ashkenaz_clm.ttf` unmodified.** Per the exception
above, the LofiPod APK is not subjected to GPL terms by virtue of
embedding this font. If a future contributor modifies the font file
itself (vs simply using it as-is), that modified font remains under
GPL v2 + the exception.

## Why this font

Used to render the Hebrew word כבוד ("kabod") on the Kabod Pack badge
in [CatalogScreen.kt](../../../java/com/lofipod/app/ui/screens/CatalogScreen.kt)'s
`KabodPackRow`. Stam fonts are the traditional sofer-quill style used
for Torah scrolls, tefillin, and mezuzot — including tagin (תגין /
kether crowns) on the letters that traditionally bear them. Picking a
Stam font for the badge matches the connotation of Kabod (weight /
glory / sacred presence) the pack name carries.

Replaced the v0.10.16 hand-drawn Compose Canvas approach which the user
correctly judged as visually subpar for a single-purpose glyph.
