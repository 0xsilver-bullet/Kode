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

## Why `ImageVector` and not a drawable

The call sites all live in `commonMain`, and `res/drawable` vector drawables are
Android-only. The multiplatform alternative, `composeResources/drawable`, is
packaged verbatim with no dead-resource elimination and pays an XML parse on
first use. Generated `ImageVector`s are compiled Kotlin: strippable by R8,
parse-free, and lazily instantiated.

## Assumptions the converter makes

`svg_to_imagevector.py` maps SVG path commands one-for-one onto Compose's
`PathBuilder`, so nothing is approximated. It only reads `<path d="…">` and
folds every subpath into a single stroked path, which holds for Tabler outline
(uniform `stroke-width: 2`, round caps and joins, `fill="none"`). A glyph that
uses `<circle>`/`<rect>`, a fill, or mixed strokes needs the generator extended
rather than silently dropping geometry — check the output when you add one.

## Eyeballing the result

`preview_sheet.py` parses the *generated* Kotlin back into SVG and lays every
glyph out on a contact sheet, so the thing you review is the committed output
rather than the inputs:

```sh
python3 preview_sheet.py && qlmanage -t -s 1400 -o /tmp /tmp/sheet.svg
open /tmp/sheet.svg.png
```
