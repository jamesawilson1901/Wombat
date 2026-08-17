/*
 * Builds test/fixture.pdf — a small AcroForm with one of each field kind the
 * filler supports, so the end-to-end test has something real to fill in.
 *
 *   node test/make-fixture.mjs
 */
import { readFileSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);
const { PDFDocument, StandardFonts, rgb } = require(join(here, '..', 'vendor', 'pdf-lib.min.js'));

const pdf = await PDFDocument.create();
const font = await pdf.embedFont(StandardFonts.Helvetica);
const page = pdf.addPage([420, 460]);
const form = pdf.getForm();

const label = (text, y, size = 11) =>
  page.drawText(text, { x: 30, y, size, font, color: rgb(0.1, 0.1, 0.1) });

page.drawText('Membership form', { x: 30, y: 415, size: 16, font });

label('Full name', 380);
form.createTextField('applicant.name').addToPage(page, { x: 30, y: 355, width: 250, height: 20 });

label('Notes', 320);
const notes = form.createTextField('applicant.notes');
notes.enableMultiline();
notes.addToPage(page, { x: 30, y: 250, width: 250, height: 62 });

label('Country', 215);
const country = form.createDropdown('applicant.country');
country.setOptions(['United Kingdom', 'Ireland', 'France']);
country.addToPage(page, { x: 30, y: 190, width: 160, height: 20 });

label('I agree to the terms', 155);
form.createCheckBox('agree').addToPage(page, { x: 190, y: 150, width: 16, height: 16 });

label('Subscribe to the newsletter', 125);
form.createCheckBox('newsletter').addToPage(page, { x: 190, y: 120, width: 16, height: 16 });

label('Membership: standard / life', 95);
const tier = form.createRadioGroup('tier');
tier.addOptionToPage('standard', page, { x: 190, y: 60, width: 16, height: 16 });
tier.addOptionToPage('life', page, { x: 230, y: 60, width: 16, height: 16 });

const out = join(here, 'fixture.pdf');
writeFileSync(out, await pdf.save());
console.log(`wrote ${out} (${readFileSync(out).length} bytes)`);
