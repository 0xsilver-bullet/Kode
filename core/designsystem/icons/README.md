# Icon sources

`KodeIcons` is generated from the SVGs in this directory, so the APK ships
exactly the glyphs the app draws — no icon-pack dependency, nothing left to be
stripped by R8 (which the release build does not enable today).

The SVGs are [Tabler outline](https://tabler.io/icons) icons, verbatim from
`tabler/tabler-icons`, kept at their upstream slugs. T3 Code uses the same set.

## Adding or replacing a glyph

1. Drop the SVG in here under its upstream Tabler slug:

   ```sh
   curl -O https://raw.githubusercontent.com/tabler/tabler-icons/main/icons/outline/<slug>.svg
   ```

2. Add a `(KodeIcons name, slug, KDoc)` row to `ICONS` in
   `generate_kodeicons.py`. The list order is the order of the generated file.

3. Regenerate, and commit both the SVG and the generated Kotlin:

   ```sh
   python3 generate_kodeicons.py \
     > ../src/commonMain/kotlin/com/silverbullet/kode/core/designsystem/KodeIcons.kt
   ```

## Brand marks

`brand/` holds the agent vendors' own logomarks, exactly as they publish them,
and `generate_brandmarks.py` turns them into `KodeBrandMarks`. These are a
different kind of thing from a Tabler glyph — filled rather than stroked, and
carrying their own colours — so they are drawn with `Image`, not a tinted
`Icon`: a brand mark recoloured to the foreground is no longer the brand's mark.
OpenCode publishes one SVG per colour scheme, so both are converted and the
theme picks between them.

The SVGs are committed byte-for-byte, including the background plate they ship
as app icons. `MARKS` in the generator carries the two things that cannot be
read off the file automatically:

- `window` — the glyph's box within the source `viewBox`, taken from the
  outermost glyph path's own extents. The generator translates it to the origin
  and centres the narrower axis, so a caller can size a mark with a square
  `Modifier.size` like any other glyph.
- `fills` — every fill the SVG uses, mapped to the Kotlin colour to draw it
  with, or to `None` to drop it. Only the background plate is dropped, and an
  unlisted fill fails the generator rather than vanishing silently.

## Why `ImageVector` and not a drawable

The call sites all live in `commonMain`, and `res/drawable` vector drawables are
Android-only. The multiplatform alternative, `composeResources/drawable`, is
packaged verbatim with no dead-resource elimination and pays an XML parse on
first use. Generated `ImageVector`s are compiled Kotlin: strippable by R8,
parse-free, and lazily instantiated.

## Assumptions the converter makes

`svg_to_imagevector.py` maps SVG path commands one-for-one onto Compose's
`PathBuilder`, so nothing is approximated. `convert` — what `KodeIcons` uses —
reads only `<path d="…">` and folds every subpath into a single stroked path,
which holds for Tabler outline (uniform `stroke-width: 2`, round caps and joins,
`fill="none"`). `shapes` — what the brand marks use — instead keeps each shape
separate with its own fill and fill rule, and understands `<rect>`. A glyph that
uses `<circle>`, an `rx` on its rect, or mixed strokes needs the converter
extended rather than silently dropping geometry — check the output when you add
one.

## Eyeballing the result

`preview_sheet.py` parses the *generated* Kotlin back into SVG and lays every
glyph and brand mark out on a contact sheet, so the thing you review is the
committed output rather than the inputs:

```sh
python3 preview_sheet.py && qlmanage -t -s 1400 -o /tmp /tmp/sheet.svg
open /tmp/sheet.svg.png
```
