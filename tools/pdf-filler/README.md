# PDF Filler

A basic form filler. Open a PDF and editable boxes snap onto the form fields
the document already defines — no dragging text around, no guessing at
coordinates. Type into them, click the checkboxes, save a real filled PDF.

Self-contained: one HTML page plus two vendored libraries. Nothing is
uploaded, and no build step or install is needed.

## Using it

Open `index.html` in a browser and pick a PDF. That works straight off the
filesystem; serving the folder is a little faster on big documents because the
PDF is then parsed in a background worker:

```
python3 -m http.server -d tools/pdf-filler 8000   # then open localhost:8000
```

- **Text fields** — click and type. Tab, or Enter, moves to the next field;
  Shift+Enter goes back.
- **Checkboxes** — click to cycle empty → ✓ → ✗ → empty. Both marks set the
  box to *checked*; the glyph is only what prints, so a form that wants an X
  still reads as ticked to anything that extracts the data later.
- **Radio buttons** — click to select, click again to clear the group.
- **Dropdowns** — pick from the choices the PDF defines.
- **Highlight fields** tints the fillable areas so you can see what is
  editable. Turn it off to preview how the page will actually print.
- **Flatten** bakes the answers in, so the result is a plain page rather than
  a form someone can still edit.
- **Save filled PDF** writes `<name>-filled.pdf` to your downloads.

## What it will not do

It fills **AcroForm** fields — the field layer a PDF carries. A scanned or
flattened document has no such layer, only a picture of a form, so there is
nothing to snap to; the page says so rather than pretending. There is no
support for drawing text at arbitrary coordinates, XFA forms, or digital
signatures.

Two smaller limits worth knowing: comb fields (the ones with a box per
character) are filled as ordinary text rather than spaced per cell, and saving
regenerates field appearances in Helvetica, so a form using an unusual font
will render its answers in Helvetica.

## How it works

`pdf.js` renders each page to a canvas and reports every widget annotation on
it — field name, kind, and rectangle in PDF space. `convertToViewportRectangle`
maps that rectangle to screen pixels, and an ordinary HTML control is
positioned exactly over it, which is what makes the overlay line up at any
zoom. Values live in a plain map keyed by field name, so a field appearing
twice stays in sync and zooming re-renders without losing anything.

`pdf-lib` writes the result back into the original bytes. Ticks and crosses
both `check()` the box; for a cross the widget's "on" appearance is then
replaced with a form XObject that strokes an X, so the mark is part of the PDF
rather than something painted over it, and it survives flattening.

One wrinkle worth naming, since it bites anything that mixes these two
libraries: pdf.js reports a radio button's *appearance state* (often `0`, `1`,
…) while pdf-lib selects by the name in the field's option list (`standard`,
`life`). `radioOption()` in `app.js` lines the two up by position.

## Layout

```
index.html      markup and toolbar
app.js          rendering, the snapped overlay, and saving
styles.css
vendor/         pdf.js 3.11.174 and pdf-lib 1.17.1, with their licences
test/           fixture generator and the end-to-end test
```

## Tests

`test/e2e.mjs` drives the real page in Chromium: it builds a fixture form,
checks the overlay lands on the field rectangles to within a pixel, cycles a
checkbox, saves, and reads the values back out of the produced PDF — including
rasterising it to confirm the tick and cross actually paint. It also checks the
page still works from `file://`.

```
cd tools/pdf-filler
npm install playwright        # only dependency, only for the test
node test/e2e.mjs
```

Set `PW_CHROMIUM` if Playwright cannot find a browser itself.

## Relation to the rest of this repo

Standalone, and deliberately so — it shares no code with the Magpie Android
app and nothing in the Gradle build refers to it.
