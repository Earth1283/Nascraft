export function h(tag, props, ...children) {
  const el = document.createElement(tag);
  if (props) {
    for (const [k, v] of Object.entries(props)) {
      if (v == null || v === false) continue;
      if (k === 'class') el.className = v;
      else if (k === 'style' && typeof v === 'object') setStyles(el, v);
      else if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2).toLowerCase(), v);
      else if (k === 'dataset') Object.assign(el.dataset, v);
      else if (v === true) el.setAttribute(k, '');
      else el.setAttribute(k, v);
    }
  }
  append(el, children);
  return el;
}

function setStyles(el, styles) {
  for (const [prop, value] of Object.entries(styles)) {
    if (prop.startsWith('--')) el.style.setProperty(prop, value);
    else el.style[prop] = value;
  }
}

function append(el, children) {
  for (const c of children) {
    if (c == null || c === false) continue;
    if (Array.isArray(c)) append(el, c);
    else el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
}

const SVG_NS = 'http://www.w3.org/2000/svg';
export function s(tag, attrs, ...children) {
  const el = document.createElementNS(SVG_NS, tag);
  if (attrs) for (const [k, v] of Object.entries(attrs)) if (v != null) el.setAttribute(k, v);
  for (const c of children) if (c != null) el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  return el;
}

export function clear(el) { while (el.firstChild) el.firstChild.remove(); return el; }

export function mount(el, ...children) { clear(el); append(el, children); return el; }

export const reducedMotion = () => matchMedia('(prefers-reduced-motion: reduce)').matches;

export function debounce(fn, ms) {
  let t;
  return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); };
}
