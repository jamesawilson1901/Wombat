/*
 * End-to-end test: drives index.html in a real browser, fills the fixture
 * form, saves, and checks the produced PDF.
 *
 *   node test/make-fixture.mjs
 *   node test/e2e.mjs
 *
 * Needs Playwright's chromium. Point PW_CHROMIUM at a browser binary if
 * Playwright cannot find one itself.
 */
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';
import { dirname, extname, join, normalize } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

await import('./make-fixture.mjs');   // keep fixture.pdf in step with the maker

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..');
const require = createRequire(import.meta.url);
const { PDFDocument, PDFName, decodePDFRawStream } = require(join(root, 'vendor', 'pdf-lib.min.js'));

const TYPES = {
  '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css',
  '.pdf': 'application/pdf', '.pfb': 'application/octet-stream',
};

let failures = 0;
function check(name, ok, detail) {
  console.log(`${ok ? 'ok  ' : 'FAIL'} ${name}${ok || !detail ? '' : ` — ${detail}`}`);
  if (!ok) failures++;
}
function eq(name, actual, expected) {
  check(name, Object.is(actual, expected), `expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
}
function near(name, actual, expected, tol) {
  check(name, Math.abs(actual - expected) <= tol, `expected ~${expected} (±${tol}), got ${actual}`);
}

function serve() {
  const server = createServer(async (req, res) => {
    const rel = normalize(decodeURIComponent(req.url.split('?')[0])).replace(/^(\.\.[/\\])+/, '');
    const file = join(root, rel === '/' ? 'index.html' : rel);
    try {
      const body = await readFile(file);
      res.writeHead(200, { 'content-type': TYPES[extname(file)] || 'application/octet-stream' });
      res.end(body);
    } catch {
      res.writeHead(404).end('not found');
    }
  });
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(server)));
}

/** Fill the fixture form in the page and return the saved bytes. */
async function fillAndSave(page, { flatten }) {
  await page.setInputFiles('#file', join(here, 'fixture.pdf'));
  await page.waitForSelector('.page canvas');
  await page.waitForFunction(() => document.querySelectorAll('.fld, .btnfld').length >= 7);

  await page.fill('[title="applicant.name"]', 'Ada Lovelace');
  await page.fill('[title="applicant.notes"]', 'First line\nSecond line');
  await page.selectOption('[title="applicant.country"]', { label: 'Ireland' });
  await page.click('[title="agree"]');                       // once  -> tick
  await page.click('[title="newsletter"]');
  await page.click('[title="newsletter"]');                  // twice -> cross
  await page.click('div[title="tier"] >> nth=1');            // the "life" widget

  if (flatten) await page.check('#flatten');

  const [download] = await Promise.all([
    page.waitForEvent('download'),
    page.click('#save'),
  ]);
  return readFile(await download.path());
}

function appearanceOps(pdf, field) {
  const widget = field.acroField.getWidgets()[0];
  const normal = widget.getAppearances()?.normal;
  const on = widget.getOnValue();
  const ref = normal?.get ? normal.get(on) : normal;
  const stream = pdf.context.lookup(ref);
  if (!stream) return '';
  let bytes;
  try {
    bytes = decodePDFRawStream(stream).decode();
  } catch {
    bytes = stream.getContents();
  }
  return Buffer.from(bytes).toString('latin1');
}

const server = await serve();
const base = `http://127.0.0.1:${server.address().port}/index.html`;
const browser = await chromium.launch({
  executablePath: process.env.PW_CHROMIUM || '/opt/pw-browsers/chromium',
});

try {
  const context = await browser.newContext({ acceptDownloads: true });
  const page = await context.newPage();
  const logs = [];
  page.on('pageerror', (e) => check(`no page error (${e.message})`, false));
  page.on('console', (m) => logs.push(m.text()));
  await page.goto(base);

  // ---- fields are discovered and snapped onto the real widget rectangles ----
  await page.setInputFiles('#file', join(here, 'fixture.pdf'));
  await page.waitForSelector('.page canvas');
  await page.waitForFunction(() => document.querySelectorAll('.fld, .btnfld').length >= 7);

  eq('field count in toolbar', await page.textContent('#count'), '6 fields');
  check('served pages parse in a background worker',
    !logs.some((l) => /fake worker/i.test(l)), logs.join(' | '));
  eq('multiline field is a textarea',
    await page.getAttribute('[title="applicant.notes"]', 'class'), 'fld');
  eq('multiline field tag',
    await page.evaluate(() => document.querySelector('[title="applicant.notes"]').tagName), 'TEXTAREA');
  eq('radio group renders both options',
    await page.locator('div[title="tier"]').count(), 2);

  // The fixture puts "agree" at PDF x=190 y=150, 16x16, on a 420x460 page, so
  // at 100% zoom the overlay must sit at (190, 460-166) with a 16px side.
  const pageBox = await page.locator('.page').boundingBox();
  const agree = await page.locator('[title="agree"]').boundingBox();
  near('checkbox snaps to field x', agree.x - pageBox.x, 190, 1);
  near('checkbox snaps to field y', agree.y - pageBox.y, 294, 1);
  near('checkbox snaps to field width', agree.width, 16, 1);
  near('checkbox snaps to field height', agree.height, 16, 1);

  // ---- clicking cycles a checkbox empty -> tick -> cross -> empty ----
  const box = page.locator('[title="newsletter"]');
  eq('checkbox starts empty', (await box.textContent()).trim(), '');
  await box.click();
  eq('one click ticks', (await box.textContent()).trim(), '✓');
  await box.click();
  eq('two clicks cross', (await box.textContent()).trim(), '✗');
  await box.click();
  eq('three clicks clear', (await box.textContent()).trim(), '');

  // ---- fill it in for real and save ----
  await page.goto(base);
  const filled = await fillAndSave(page, { flatten: false });
  const out = await PDFDocument.load(filled);
  const form = out.getForm();

  eq('text field saved', form.getTextField('applicant.name').getText(), 'Ada Lovelace');
  eq('multiline text saved', form.getTextField('applicant.notes').getText(), 'First line\nSecond line');
  eq('dropdown saved', form.getDropdown('applicant.country').getSelected()[0], 'Ireland');
  eq('ticked box is checked', form.getCheckBox('agree').isChecked(), true);
  eq('crossed box is also checked', form.getCheckBox('newsletter').isChecked(), true);
  eq('radio saved', form.getRadioGroup('tier').getSelected(), 'life');

  const crossOps = appearanceOps(out, form.getCheckBox('newsletter'));
  const tickOps = appearanceOps(out, form.getCheckBox('agree'));
  check('crossed box draws two stroked lines', (crossOps.match(/\bS\b/g) || []).length === 2, crossOps.slice(0, 120));
  check('ticked box keeps its normal glyph', !/\bS\b.*\bS\b/s.test(tickOps), tickOps.slice(0, 120));

  // ---- the marks actually paint: rasterise the saved PDF and count ink ----
  const ink = await page.evaluate(async (b64) => {
    const bytes = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
    const doc = await pdfjsLib.getDocument({ data: bytes, standardFontDataUrl: 'vendor/standard_fonts/' }).promise;
    const pg = await doc.getPage(1);
    const viewport = pg.getViewport({ scale: 2 });
    const canvas = document.createElement('canvas');
    canvas.width = viewport.width;
    canvas.height = viewport.height;
    await pg.render({ canvasContext: canvas.getContext('2d'), viewport }).promise;
    // Fraction of dark pixels inside a PDF-space rectangle.
    const darkness = (x, y, w, h) => {
      const [a, b, c, d] = viewport.convertToViewportRectangle([x, y, x + w, y + h]);
      const data = canvas.getContext('2d').getImageData(
        Math.min(a, c), Math.min(b, d), Math.abs(c - a), Math.abs(d - b)).data;
      let dark = 0;
      for (let i = 0; i < data.length; i += 4) if (data[i] < 128) dark++;
      return dark / (data.length / 4);
    };
    return {
      cross: darkness(190, 120, 16, 16),
      tick: darkness(190, 150, 16, 16),
      empty: darkness(330, 120, 16, 16),
    };
  }, filled.toString('base64'));

  check('cross mark is painted', ink.cross > 0.05, `darkness ${ink.cross.toFixed(3)}`);
  check('tick mark is painted', ink.tick > 0.02, `darkness ${ink.tick.toFixed(3)}`);
  check('untouched area stays blank', ink.empty < 0.005, `darkness ${ink.empty.toFixed(3)}`);

  // ---- flatten produces a PDF with no form left ----
  await page.goto(base);
  const flat = await PDFDocument.load(await fillAndSave(page, { flatten: true }));
  eq('flattened output has no fields', flat.getForm().getFields().length, 0);

  // ---- and it all works straight off the filesystem, with no server ----
  const local = await context.newPage();
  await local.goto(`file://${join(root, 'index.html')}`);
  await local.setInputFiles('#file', join(here, 'fixture.pdf'));
  await local.waitForSelector('.page canvas', { timeout: 20000 });
  await local.waitForFunction(() => document.querySelectorAll('.fld, .btnfld').length >= 7);
  eq('file:// finds the same fields', await local.textContent('#count'), '6 fields');
} finally {
  await browser.close();
  server.close();
}

console.log(failures ? `\n${failures} check(s) failed` : '\nall checks passed');
process.exit(failures ? 1 : 0);
