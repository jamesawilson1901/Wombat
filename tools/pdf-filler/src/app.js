'use strict';

/*
 * PDF Filler — snaps editable controls onto the AcroForm fields a PDF already
 * defines, then writes the answers back into a real PDF.
 *
 * pdf.js renders the pages and reports where every widget annotation sits;
 * pdf-lib writes the values. build.mjs inlines both into one HTML file, so
 * this runs offline with no external references of any kind.
 */

const {
  PDFDocument, StandardFonts, PDFName, rgb,
  pushGraphicsState, popGraphicsState, moveTo, lineTo, stroke,
  setLineWidth, setStrokingColor, setLineCap, LineCapStyle,
} = PDFLib;

const MIN_SCALE = 0.4;
const MAX_SCALE = 3.0;

/** Checkbox click order. Both marks mean "checked"; the glyph is cosmetic. */
const CHECK_STATES = ['off', 'check', 'cross'];
const GLYPH = { off: '', check: '✓', cross: '✗' };

const state = {
  bytes: null,        // pristine copy of the original file, for pdf-lib
  doc: null,          // pdf.js document
  name: 'document.pdf',
  scale: 1,
  values: new Map(),  // field name -> current value
  initial: new Map(), // field name -> value the PDF shipped with
  fields: new Map(),  // field name -> {type}
  order: [],          // focusable controls, in reading order
};

const el = {
  file: document.getElementById('file'),
  pages: document.getElementById('pages'),
  drop: document.getElementById('drop'),
  docname: document.getElementById('docname'),
  tools: document.getElementById('doc-tools'),
  status: document.getElementById('status'),
  count: document.getElementById('count'),
  zoomlabel: document.getElementById('zoomlabel'),
  highlight: document.getElementById('highlight'),
  flatten: document.getElementById('flatten'),
};

// ---------------------------------------------------------------- utilities

function setStatus(message, kind) {
  if (!message) {
    el.status.hidden = true;
    el.status.textContent = '';
    return;
  }
  el.status.hidden = false;
  el.status.textContent = message;
  el.status.classList.toggle('error', kind === 'error');
}

function px(n) { return `${n}px`; }

function clamp(n, lo, hi) { return Math.min(hi, Math.max(lo, n)); }

/** pdf.js hands back strings, arrays or null depending on the field. */
function firstValue(v) {
  if (Array.isArray(v)) return v.length ? String(v[0]) : '';
  return v == null ? '' : String(v);
}

// ------------------------------------------------------------- loading a PDF

async function loadFile(file) {
  if (!file) return;
  if (file.type && file.type !== 'application/pdf' && !/\.pdf$/i.test(file.name)) {
    setStatus(`${file.name} is not a PDF.`, 'error');
    return;
  }
  setStatus('Opening…');
  try {
    const buffer = await file.arrayBuffer();
    await openBytes(buffer, file.name);
  } catch (err) {
    console.error(err);
    setStatus(`Could not open ${file.name}: ${err.message}`, 'error');
  }
}

async function openBytes(buffer, name) {
  state.bytes = buffer.slice(0);   // pdf.js may detach the buffer it is given
  state.name = name || 'document.pdf';
  state.values = new Map();
  state.initial = new Map();
  state.fields = new Map();
  state.scale = 1;

  // The worker script is bundled into this page, so pdf.js finds it as
  // globalThis.pdfjsWorker and parses here rather than in a background thread.
  state.doc = await pdfjsLib.getDocument({
    data: new Uint8Array(buffer),
    isEvalSupported: false,
  }).promise;

  el.drop.hidden = true;
  el.tools.hidden = false;
  el.docname.textContent = state.name;
  await render();

  const n = state.fields.size;
  if (n === 0) {
    setStatus(
      'This PDF has no fillable form fields, so there is nothing to snap to. ' +
      'Scanned and flattened documents look like a form but are just an image.',
      'error');
  } else {
    setStatus('');
  }
}

// ----------------------------------------------------------------- rendering

async function render() {
  const keepScroll = window.scrollY;
  el.pages.textContent = '';
  state.order = [];
  el.zoomlabel.textContent = `${Math.round(state.scale * 100)}%`;
  el.pages.classList.toggle('hl', el.highlight.checked);

  for (let i = 1; i <= state.doc.numPages; i++) {
    const page = await state.doc.getPage(i);
    const viewport = page.getViewport({ scale: state.scale });

    const wrap = document.createElement('div');
    wrap.className = 'page';
    wrap.style.width = px(viewport.width);
    wrap.style.height = px(viewport.height);

    const canvas = document.createElement('canvas');
    const dpr = window.devicePixelRatio || 1;
    canvas.width = Math.floor(viewport.width * dpr);
    canvas.height = Math.floor(viewport.height * dpr);
    canvas.style.width = px(viewport.width);
    canvas.style.height = px(viewport.height);

    const layer = document.createElement('div');
    layer.className = 'layer';

    wrap.append(canvas, layer);
    el.pages.append(wrap);

    await page.render({
      canvasContext: canvas.getContext('2d'),
      viewport,
      transform: dpr === 1 ? null : [dpr, 0, 0, dpr, 0, 0],
    }).promise;

    const annotations = await page.getAnnotations({ intent: 'display' });
    for (const a of annotations) buildControl(a, viewport, layer);
  }

  el.count.textContent = state.fields.size === 1 ? '1 field' : `${state.fields.size} fields`;
  window.scrollTo(0, keepScroll);
}

/** Which of our four control kinds an annotation maps to, or null to skip it. */
function controlKind(a) {
  if (a.subtype !== 'Widget' || !a.fieldName || a.hidden) return null;
  switch (a.fieldType) {
    case 'Tx': return 'text';
    case 'Ch': return 'select';
    case 'Btn':
      if (a.pushButton) return null;
      return a.radioButton ? 'radio' : 'check';
    default: return null;   // signatures and anything exotic
  }
}

/** Seed a field's value from what the PDF already had in it. */
function seed(key, kind, a) {
  if (state.values.has(key)) return;
  let v;
  if (kind === 'check') {
    const cur = firstValue(a.fieldValue);
    v = cur && cur !== 'Off' ? 'check' : 'off';
  } else if (kind === 'radio') {
    v = firstValue(a.fieldValue) === String(a.buttonValue) ? String(a.buttonValue) : null;
  } else if (kind === 'select') {
    // Choices are held as an index: a PDF's export value and the name pdf-lib
    // selects a choice by are not always the same string.
    const cur = firstValue(a.fieldValue);
    const i = (a.options || []).findIndex(
      (o) => String(o.exportValue) === cur || String(o.displayValue) === cur);
    v = i >= 0 ? String(i) : '';
  } else {
    v = firstValue(a.fieldValue);
  }
  state.values.set(key, v);
  state.initial.set(key, v);
}

function buildControl(a, viewport, layer) {
  const kind = controlKind(a);
  if (!kind) return;

  const r = viewport.convertToViewportRectangle(a.rect);
  const left = Math.min(r[0], r[2]);
  const top = Math.min(r[1], r[3]);
  const width = Math.abs(r[2] - r[0]);
  const height = Math.abs(r[3] - r[1]);
  if (width < 3 || height < 3) return;   // zero-size widgets are not clickable

  const key = a.fieldName;
  seed(key, kind, a);
  state.fields.set(key, { type: kind });

  const node = kind === 'check' || kind === 'radio'
    ? buildButton(a, kind, key, Math.min(width, height))
    : buildInput(a, kind, key, height);

  node.style.left = px(left);
  node.style.top = px(top);
  node.style.width = px(width);
  node.style.height = px(height);
  node.title = key;
  layer.append(node);
  state.order.push(node);
}

function buildInput(a, kind, key, height) {
  const value = state.values.get(key) ?? '';
  let node;

  if (kind === 'select') {
    node = document.createElement('select');
    const blank = document.createElement('option');
    blank.value = '';
    blank.textContent = '';
    node.append(blank);
    (a.options || []).forEach((opt, i) => {
      const o = document.createElement('option');
      o.value = String(i);
      o.textContent = String(opt.displayValue ?? opt.exportValue ?? '');
      node.append(o);
    });
    node.value = value;
    node.addEventListener('change', () => state.values.set(key, node.value));
  } else if (a.multiLine) {
    node = document.createElement('textarea');
    node.value = value;
    node.addEventListener('input', () => state.values.set(key, node.value));
  } else {
    node = document.createElement('input');
    node.type = 'text';
    node.value = value;
    node.addEventListener('input', () => state.values.set(key, node.value));
  }

  node.className = 'fld';
  // A single-line box is sized to its own height; a multiline one has to be
  // sized to a line of text instead, or it overflows the box.
  node.style.fontSize = px(a.multiLine
    ? clamp(11 * state.scale, 7, 24)
    : clamp(height * 0.62, 7, 22));
  if (a.readOnly) {
    if (kind === 'select') node.disabled = true;
    else node.readOnly = true;
  }
  if (a.maxLen > 0 && kind !== 'select') node.maxLength = a.maxLen;
  if (a.textAlignment === 1) node.style.textAlign = 'center';
  if (a.textAlignment === 2) node.style.textAlign = 'right';
  return node;
}

function buildButton(a, kind, key, size) {
  const node = document.createElement('div');
  node.className = kind === 'radio' ? 'btnfld radio' : 'btnfld';
  node.tabIndex = a.readOnly ? -1 : 0;
  node.setAttribute('role', kind === 'radio' ? 'radio' : 'checkbox');
  node.style.fontSize = px(clamp(size * 0.78, 7, 30));

  const buttonValue = kind === 'radio' ? String(a.buttonValue) : null;

  const paint = () => {
    const v = state.values.get(key);
    if (kind === 'radio') {
      const on = v === buttonValue;
      node.textContent = on ? '●' : '';
      node.setAttribute('aria-checked', String(on));
    } else {
      node.textContent = GLYPH[v] || '';
      node.setAttribute('aria-checked', String(v !== 'off'));
    }
  };

  if (a.readOnly) {
    node.classList.add('readonly');
  } else {
    const activate = () => {
      if (kind === 'radio') {
        const on = state.values.get(key) === buttonValue;
        state.values.set(key, on ? null : buttonValue);
        // Repaint the whole group: selecting one clears its siblings.
        for (const sib of state.order) if (sib.repaint && sib.dataset.key === key) sib.repaint();
      } else {
        const i = CHECK_STATES.indexOf(state.values.get(key));
        state.values.set(key, CHECK_STATES[(i + 1) % CHECK_STATES.length]);
        paint();
      }
    };
    node.addEventListener('click', activate);
    node.addEventListener('keydown', (e) => {
      if (e.key === ' ' || e.key === 'Enter') { e.preventDefault(); activate(); }
    });
  }

  node.dataset.key = key;
  node.repaint = paint;
  paint();
  return node;
}

// -------------------------------------------------------------------- saving

/** A form XObject drawing an X across its own box. */
function crossAppearance(pdf, width, height) {
  const pad = Math.min(width, height) * 0.2;
  const lw = Math.max(0.7, Math.min(width, height) * 0.11);
  const x1 = pad, y1 = pad, x2 = width - pad, y2 = height - pad;
  const ops = [
    pushGraphicsState(),
    setStrokingColor(rgb(0, 0, 0)),
    setLineWidth(lw),
    setLineCap(LineCapStyle.Round),
    moveTo(x1, y1), lineTo(x2, y2), stroke(),
    moveTo(x1, y2), lineTo(x2, y1), stroke(),
    popGraphicsState(),
  ];
  return pdf.context.register(blankBox(pdf, width, height, ops));
}

function blankBox(pdf, width, height, ops) {
  return pdf.context.formXObject(ops || [], {
    BBox: pdf.context.obj([0, 0, width, height]),
    Matrix: pdf.context.obj([1, 0, 0, 1, 0, 0]),
    Resources: pdf.context.obj({}),
  });
}

/**
 * Replace a checked box's own glyph with an X. The field stays checked — only
 * how it prints changes.
 */
function drawCross(pdf, checkBox) {
  for (const widget of checkBox.acroField.getWidgets()) {
    const on = widget.getOnValue();
    if (!on) continue;
    const rect = widget.getRectangle();
    const w = Math.abs(rect.width);
    const h = Math.abs(rect.height);
    if (w < 1 || h < 1) continue;

    const appearance = pdf.context.obj({});
    appearance.set(on, crossAppearance(pdf, w, h));
    appearance.set(PDFName.of('Off'), pdf.context.register(blankBox(pdf, w, h)));
    widget.setNormalAppearance(appearance);
    widget.dict.set(PDFName.of('AS'), on);
  }
}

/**
 * pdf.js reports the appearance state a radio widget turns on ("0", "1", …)
 * while pdf-lib selects by the name in the field's option list ("standard").
 * Line the two up by position; fall back to the raw value if they already agree.
 */
function radioOption(group, onValue) {
  const options = group.getOptions();
  const i = group.acroField.getWidgets().findIndex((w) => {
    const on = w.getOnValue();
    return on && on.decodeText() === onValue;
  });
  return i >= 0 && options[i] != null ? options[i] : onValue;
}

async function buildFilledPdf() {
  const pdf = await PDFDocument.load(state.bytes, {
    ignoreEncryption: true,
    updateMetadata: false,
  });
  const form = pdf.getForm();
  const warnings = [];
  const crossed = [];

  for (const [key, meta] of state.fields) {
    const value = state.values.get(key);
    try {
      if (meta.type === 'text') {
        form.getTextField(key).setText(value || '');
      } else if (meta.type === 'select') {
        const dropdown = form.getDropdown(key);
        if (value === '' || value == null) dropdown.clear();
        else {
          const options = dropdown.getOptions();
          const i = Number(value);
          dropdown.select(options[i] != null ? options[i] : value);
        }
      } else if (meta.type === 'check') {
        const box = form.getCheckBox(key);
        if (value === 'off') box.uncheck();
        else { box.check(); if (value === 'cross') crossed.push(box); }
      } else if (meta.type === 'radio') {
        const group = form.getRadioGroup(key);
        if (value) group.select(radioOption(group, value)); else group.clear();
      }
    } catch (err) {
      warnings.push(`${key}: ${err.message}`);
    }
  }

  // Generate appearances now so the cross overrides below are not regenerated
  // out from under us when the document is saved.
  let appearancesDone = false;
  try {
    form.updateFieldAppearances(await pdf.embedFont(StandardFonts.Helvetica));
    appearancesDone = true;
  } catch (err) {
    warnings.push(`appearances: ${err.message}`);
  }

  if (appearancesDone) {
    for (const box of crossed) drawCross(pdf, box);
  } else if (crossed.length) {
    warnings.push('crossed boxes were saved as ordinary ticks');
  }

  if (el.flatten.checked) {
    try {
      form.flatten({ updateFieldAppearances: false });
    } catch (err) {
      warnings.push(`could not flatten, saved as an editable form instead (${err.message})`);
    }
  }

  const bytes = await pdf.save({ updateFieldAppearances: !appearancesDone });
  return { bytes, warnings };
}

async function save() {
  if (!state.doc) return;
  setStatus('Saving…');
  try {
    const { bytes, warnings } = await buildFilledPdf();
    const blob = new Blob([bytes], { type: 'application/pdf' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = state.name.replace(/\.pdf$/i, '') + '-filled.pdf';
    document.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 10000);
    setStatus(warnings.length ? `Saved, with problems — ${warnings.join('; ')}` : '',
      warnings.length ? 'error' : null);
  } catch (err) {
    console.error(err);
    setStatus(`Could not save: ${err.message}`, 'error');
  }
}

// -------------------------------------------------------------------- wiring

function focusStep(from, delta) {
  const i = state.order.indexOf(from);
  if (i < 0) return;
  for (let j = i + delta; j >= 0 && j < state.order.length; j += delta) {
    const next = state.order[j];
    if (next.tabIndex !== -1 && !next.disabled && !next.readOnly) {
      next.focus();
      if (next.select) next.select();
      return;
    }
  }
}

function setZoom(next) {
  const scale = clamp(Number(next.toFixed(2)), MIN_SCALE, MAX_SCALE);
  if (scale === state.scale) return;
  state.scale = scale;
  render();
}

document.getElementById('open').addEventListener('click', () => el.file.click());
document.getElementById('open2').addEventListener('click', () => el.file.click());
el.file.addEventListener('change', () => {
  loadFile(el.file.files[0]);
  el.file.value = '';
});

document.getElementById('save').addEventListener('click', save);
document.getElementById('zoomin').addEventListener('click', () => setZoom(state.scale + 0.2));
document.getElementById('zoomout').addEventListener('click', () => setZoom(state.scale - 0.2));
document.getElementById('next').addEventListener('click', () => focusStep(document.activeElement, 1));
document.getElementById('prev').addEventListener('click', () => focusStep(document.activeElement, -1));

el.highlight.addEventListener('change', () => {
  el.pages.classList.toggle('hl', el.highlight.checked);
});

document.getElementById('reset').addEventListener('click', () => {
  if (!state.doc) return;
  state.values = new Map(state.initial);
  render();
  setStatus('');
});

document.addEventListener('keydown', (e) => {
  if (e.key !== 'Enter') return;
  const t = e.target;
  if (!state.order.includes(t)) return;
  if (t.tagName === 'TEXTAREA' || t.classList.contains('btnfld')) return;
  e.preventDefault();
  focusStep(t, e.shiftKey ? -1 : 1);
});

for (const type of ['dragenter', 'dragover']) {
  document.addEventListener(type, (e) => {
    e.preventDefault();
    el.drop.classList.add('over');
  });
}
document.addEventListener('dragleave', (e) => {
  if (e.relatedTarget === null) el.drop.classList.remove('over');
});
document.addEventListener('drop', (e) => {
  e.preventDefault();
  el.drop.classList.remove('over');
  loadFile(e.dataTransfer?.files?.[0]);
});
