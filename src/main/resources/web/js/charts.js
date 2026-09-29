import { h, s, mount } from './dom.js';
import { t } from './i18n.js';

const PAD = { top: 12, right: 12, bottom: 26, left: 56 };

function niceTicks(min, max, count = 4) {
  if (!isFinite(min) || !isFinite(max)) return [0];
  if (min === max) { const d = Math.abs(min) * 0.05 || 1; min -= d; max += d; }
  const span = max - min;
  const step0 = span / count;
  const mag = 10 ** Math.floor(Math.log10(step0));
  const step = [1, 2, 2.5, 5, 10].map(m => m * mag).find(s => s >= step0) || step0;
  const ticks = [];
  for (let v = Math.floor(min / step) * step; v <= max + step * 0.5; v += step) ticks.push(+v.toFixed(12));
  return ticks;
}

function extent(arrays, includeZero) {
  let lo = Infinity, hi = -Infinity;
  for (const a of arrays) for (const v of a) if (v != null && isFinite(v)) { if (v < lo) lo = v; if (v > hi) hi = v; }
  if (includeZero) { lo = Math.min(lo, 0); hi = Math.max(hi, 0); }
  if (lo === Infinity) return [0, 1];
  return [lo, hi];
}

function frame({ title, note, legend, onTable }) {
  const plot = h('div', { class: 'chart-plot' });
  const tableHost = h('div', { class: 'chart-table', hidden: true });
  const toggle = h('button', { class: 'link-btn', type: 'button', 'aria-expanded': 'false' }, t('chart.showTable'));
  toggle.addEventListener('click', () => {
    const show = tableHost.hidden;
    tableHost.hidden = !show;
    plot.hidden = show;
    toggle.textContent = show ? t('chart.showChart') : t('chart.showTable');
    toggle.setAttribute('aria-expanded', String(show));
    if (show && !tableHost.firstChild) onTable(tableHost);
  });
  const legendEl = legend && legend.length > 1
    ? h('ul', { class: 'legend' }, legend.map(l => h('li', null,
        h('span', { class: `key key-${l.shape || 'line'}`, style: { '--c': l.color } }), l.name)))
    : null;
  const figure = h('figure', { class: 'chart' },
    h('figcaption', null, h('span', { class: 'chart-title' }, title), note ? h('span', { class: 'chart-note' }, note) : null, toggle),
    legendEl, plot, tableHost);
  return { figure, plot, tableHost };
}

function table(host, headers, rows) {
  const tbl = h('table', { class: 'data' },
    h('thead', null, h('tr', null, headers.map(x => h('th', { scope: 'col' }, x)))),
    h('tbody', null, rows.map(r => h('tr', null, r.map((c, i) => (i === 0 ? h('th', { scope: 'row' }, c) : h('td', null, c)))))));
  mount(host, h('div', { class: 'table-scroll' }, tbl));
}

function onResize(el, draw) {
  let w = 0;
  const ro = new ResizeObserver(entries => {
    const nw = Math.round(entries[0].contentRect.width);
    if (nw > 0 && nw !== w) { w = nw; draw(nw); }
  });
  ro.observe(el);
  return ro;
}

function tooltip(container) {
  const tip = h('div', { class: 'tip', role: 'status', 'aria-live': 'polite', hidden: true });
  container.append(tip);
  return {
    el: tip,
    show(x, y, head, rows) {
      mount(tip, h('div', { class: 'tip-head' }, head),
        rows.map(r => h('div', { class: 'tip-row' },
          h('span', { class: 'key key-line', style: { '--c': r.color } }),
          h('strong', null, r.value), h('span', { class: 'tip-name' }, r.name))));
      tip.hidden = false;
      const cw = container.clientWidth;
      const tw = tip.offsetWidth;
      tip.style.left = `${Math.max(0, Math.min(cw - tw, x + 12 > cw - tw ? x - tw - 12 : x + 12))}px`;
      tip.style.top = `${Math.max(0, y - 8)}px`;
    },
    hide() { tip.hidden = true; },
  };
}

export function lineChart(opts) {
  const { x, series, height = 220 } = opts;
  const yf = opts.y || (v => String(v));
  const xf = opts.xLabel || (v => String(v));
  const f = frame({
    title: opts.title, note: opts.note,
    legend: series.map(s => ({ name: s.name, color: s.color })),
    onTable: host => table(host, [t('chart.time'), ...series.map(s => s.name)],
      x.map((xv, i) => [xf(xv), ...series.map(s => yf(s.values[i]))]).reverse()),
  });
  f.plot.style.position = 'relative';
  if (!x.length) { mount(f.plot, h('p', { class: 'empty' }, opts.empty || t('chart.empty'))); return f.figure; }

  const tip = tooltip(f.plot);
  onResize(f.plot, width => {
    const H = height, W = width;
    const iw = W - PAD.left - PAD.right, ih = H - PAD.top - PAD.bottom;
    let [lo, hi] = extent(series.map(s => s.values), opts.zero);
    if (opts.reference) { lo = Math.min(lo, opts.reference.value); hi = Math.max(hi, opts.reference.value); }
    const ticks = niceTicks(lo, hi);
    const y0 = ticks[0], y1 = ticks[ticks.length - 1];
    const xmin = x[0], xmax = x[x.length - 1] === x[0] ? x[0] + 1 : x[x.length - 1];
    const X = v => PAD.left + ((v - xmin) / (xmax - xmin)) * iw;
    const Y = v => PAD.top + ih - ((v - y0) / (y1 - y0 || 1)) * ih;

    const svg = s('svg', { viewBox: `0 0 ${W} ${H}`, width: W, height: H, role: 'img', tabindex: '0', 'aria-label': opts.title });
    const grid = s('g', { class: 'grid' });
    for (const tv of ticks) {
      grid.append(s('line', { x1: PAD.left, x2: W - PAD.right, y1: Y(tv), y2: Y(tv) }));
      grid.append(s('text', { x: PAD.left - 8, y: Y(tv), 'text-anchor': 'end', 'dominant-baseline': 'middle', class: 'tick' }, yf(tv)));
    }
    const xticks = Math.max(2, Math.min(6, Math.floor(iw / 110)));
    for (let k = 0; k <= xticks; k++) {
      const v = xmin + ((xmax - xmin) * k) / xticks;
      grid.append(s('text', { x: X(v), y: H - 6, 'text-anchor': k === 0 ? 'start' : k === xticks ? 'end' : 'middle', class: 'tick' }, xf(v)));
    }
    svg.append(grid);

    if (opts.reference) {
      const ry = Y(opts.reference.value);
      svg.append(s('line', { class: 'ref', x1: PAD.left, x2: W - PAD.right, y1: ry, y2: ry }));
      svg.append(s('text', { class: 'ref-label', x: PAD.left + 6, y: ry - 6, 'text-anchor': 'start' }, opts.reference.label));
    }

    for (const ser of series) {
      let d = '';
      let started = false;
      ser.values.forEach((v, i) => {
        if (v == null || !isFinite(v)) { started = false; return; }
        d += `${started ? 'L' : 'M'}${X(x[i]).toFixed(1)},${Y(v).toFixed(1)}`;
        started = true;
      });
      if (opts.area && series.length === 1 && d) {
        const base = Y(Math.max(y0, Math.min(y1, opts.zero ? 0 : y0)));
        svg.append(s('path', { class: 'area', d: `${d}L${X(xmax).toFixed(1)},${base}L${X(xmin).toFixed(1)},${base}Z`, style: `--c:${ser.color}` }));
      }
      svg.append(s('path', { class: 'line', d, style: `--c:${ser.color}` }));
      const li = ser.values.length - 1;
      if (li >= 0 && ser.values[li] != null) {
        svg.append(s('circle', { class: 'dot', cx: X(x[li]), cy: Y(ser.values[li]), r: 4, style: `--c:${ser.color}` }));
      }
    }
    if (series.length === 1) {
      const li = series[0].values.length - 1;
      const v = series[0].values[li];
      if (v != null) svg.append(s('text', { class: 'end-label', x: Math.min(X(x[li]) - 8, W - PAD.right - 4), y: Y(v) - 10, 'text-anchor': 'end' }, yf(v)));
    }

    const cross = s('line', { class: 'cross', y1: PAD.top, y2: PAD.top + ih, visibility: 'hidden' });
    const marks = series.map(ser => s('circle', { class: 'dot', r: 4, visibility: 'hidden', style: `--c:${ser.color}` }));
    svg.append(cross, ...marks);

    let idx = -1;
    const showAt = i => {
      idx = Math.max(0, Math.min(x.length - 1, i));
      const cx = X(x[idx]);
      cross.setAttribute('x1', cx); cross.setAttribute('x2', cx); cross.setAttribute('visibility', 'visible');
      series.forEach((ser, k) => {
        const v = ser.values[idx];
        if (v == null) { marks[k].setAttribute('visibility', 'hidden'); return; }
        marks[k].setAttribute('cx', cx); marks[k].setAttribute('cy', Y(v)); marks[k].setAttribute('visibility', 'visible');
      });
      tip.show(cx, PAD.top, xf(x[idx]), series.map(ser => ({ name: ser.name, color: ser.color, value: yf(ser.values[idx]) })));
    };
    const nearest = px => {
      const target = xmin + ((px - PAD.left) / iw) * (xmax - xmin);
      let lo2 = 0, hi2 = x.length - 1;
      while (hi2 - lo2 > 1) { const m = (lo2 + hi2) >> 1; if (x[m] < target) lo2 = m; else hi2 = m; }
      return Math.abs(x[lo2] - target) <= Math.abs(x[hi2] - target) ? lo2 : hi2;
    };
    const hide = () => { cross.setAttribute('visibility', 'hidden'); marks.forEach(m => m.setAttribute('visibility', 'hidden')); tip.hide(); };
    svg.addEventListener('pointermove', e => {
      const r = svg.getBoundingClientRect();
      showAt(nearest(((e.clientX - r.left) / r.width) * W));
    });
    svg.addEventListener('pointerleave', hide);
    svg.addEventListener('blur', hide);
    svg.addEventListener('keydown', e => {
      if (e.key === 'ArrowLeft' || e.key === 'ArrowRight') {
        e.preventDefault();
        showAt((idx < 0 ? x.length - 1 : idx) + (e.key === 'ArrowLeft' ? -1 : 1));
      } else if (e.key === 'Home') { e.preventDefault(); showAt(0); }
      else if (e.key === 'End') { e.preventDefault(); showAt(x.length - 1); }
    });
    mount(f.plot, svg, tip.el);
  });
  return f.figure;
}

export function barChart(opts) {
  const { rows, series } = opts;
  const vf = opts.value || (v => String(v));
  const f = frame({
    title: opts.title, note: opts.note,
    legend: series.map(s => ({ name: s.name, color: s.color, shape: 'rect' })),
    onTable: host => table(host, [opts.labelHeader || '', ...series.map(s => s.name)], rows.map(r => [r.label, ...r.values.map(vf)])),
  });
  if (!rows.length) { mount(f.plot, h('p', { class: 'empty' }, opts.empty || t('chart.empty'))); return f.figure; }

  const base = opts.diverging ? opts.diverging.base : 0;
  let lo = base, hi = base;
  for (const r of rows) for (const v of r.values) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
  const span = hi - lo || 1;
  const list = h('div', { class: `bars${series.length > 1 ? ' bars-grouped' : ''}` });
  for (const r of rows) {
    const cells = r.values.map((v, i) => {
      const left = ((Math.min(v, base) - lo) / span) * 100;
      const width = Math.max(0.5, (Math.abs(v - base) / span) * 100);
      const color = opts.diverging ? (v >= base ? opts.diverging.up : opts.diverging.down) : series[i].color;
      const side = v >= base ? 'pos' : 'neg';
      return h('div', { class: 'bar-track' },
        h('span', { class: `bar bar-${side}`, style: { left: `${left}%`, width: `${width}%`, '--c': color } }),
        h('span', { class: `bar-value ${side}`, style: side === 'pos' ? { left: `calc(${left + width}% + 6px)` } : { right: `calc(${100 - left}% + 6px)` } }, vf(v)));
    });
    list.append(h('div', {
      class: 'bar-row', tabindex: '0',
      'aria-label': `${r.label}: ${r.values.map((v, i) => `${series[i]?.name || ''} ${vf(v)}`).join(', ')}`,
    }, h('span', { class: 'bar-label', title: r.label }, r.label), h('div', { class: 'bar-cells' }, cells)));
  }
  if (opts.diverging) {
    list.style.setProperty('--base', `${((base - lo) / span) * 100}%`);
    list.classList.add('bars-diverging');
  }
  mount(f.plot, list);
  return f.figure;
}

export function columnChart(opts) {
  const { labels, values } = opts;
  const vf = opts.value || (v => String(v));
  const f = frame({
    title: opts.title, note: opts.note,
    onTable: host => table(host, [opts.labelHeader || '', opts.valueHeader || ''], labels.map((l, i) => [l, vf(values[i])])),
  });
  const max = Math.max(...values, 0);
  if (!max) { mount(f.plot, h('p', { class: 'empty' }, opts.empty || t('chart.empty'))); return f.figure; }
  f.plot.style.position = 'relative';
  const tip = tooltip(f.plot);
  const cols = h('div', { class: 'cols', style: { '--n': labels.length } });
  labels.forEach((l, i) => {
    const pctH = (values[i] / max) * 100;
    const col = h('div', { class: 'col', tabindex: '0', 'aria-label': `${l}: ${vf(values[i])}` },
      h('span', { class: 'col-bar', style: { height: `${Math.max(pctH, values[i] > 0 ? 2 : 0)}%`, '--c': opts.color } }),
      h('span', { class: 'col-label' }, i % (opts.labelEvery || 1) === 0 ? l : ''));
    const show = () => tip.show(col.offsetLeft + col.offsetWidth / 2, 0, l, [{ name: opts.valueHeader || '', color: opts.color, value: vf(values[i]) }]);
    col.addEventListener('pointerenter', show);
    col.addEventListener('focus', show);
    col.addEventListener('pointerleave', () => tip.hide());
    col.addEventListener('blur', () => tip.hide());
    cols.append(col);
  });
  f.plot.prepend(cols);
  return f.figure;
}

export function sparkline(values, { width = 64, height = 18, className = 'spark' } = {}) {
  const v = values.filter(x => x != null && isFinite(x));
  const svg = s('svg', { class: className, viewBox: `0 0 ${width} ${height}`, width, height, 'aria-hidden': 'true', preserveAspectRatio: 'none' });
  if (v.length < 2) return svg;
  let lo = Math.min(...v), hi = Math.max(...v);
  if (hi === lo) { hi += 1; lo -= 1; }
  const X = i => 1 + (i / (v.length - 1)) * (width - 4);
  const Y = y => height - 2 - ((y - lo) / (hi - lo)) * (height - 4);
  svg.append(s('path', { d: v.map((y, i) => `${i ? 'L' : 'M'}${X(i).toFixed(1)},${Y(y).toFixed(1)}`).join('') }));
  svg.append(s('circle', { cx: X(v.length - 1), cy: Y(v[v.length - 1]), r: 2 }));
  return svg;
}

export function updateSparkline(svg, values) {
  const fresh = sparkline(values, { width: +svg.getAttribute('width'), height: +svg.getAttribute('height'), className: svg.getAttribute('class') });
  svg.replaceChildren(...fresh.childNodes);
}
