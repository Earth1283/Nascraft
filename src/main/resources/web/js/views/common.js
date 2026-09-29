import { h } from '../dom.js';
import { t, signedPctPoints, money, compact } from '../i18n.js';
import { sparkline } from '../charts.js';

export function icon(id, size = 32) {
  const img = h('img', { class: 'item-icon', src: `/api/item/${encodeURIComponent(id)}/icon`, alt: '', width: size, height: size, loading: 'lazy', decoding: 'async' });
  img.addEventListener('error', () => img.replaceWith(monogram(id, size)), { once: true });
  return img;
}

function monogram(id, size) {
  const letters = id.split('_').map(w => w[0]).join('').slice(0, 2).toUpperCase();
  return h('span', { class: 'item-monogram', 'aria-hidden': 'true', style: { width: `${size}px`, height: `${size}px`, fontSize: `${Math.round(size * 0.42)}px` } }, letters);
}

export function change(v, digits = 1) {
  const dir = v > 0.05 ? 'up' : v < -0.05 ? 'down' : 'flat';
  return h('span', { class: `chg ${dir}` },
    h('span', { 'aria-hidden': 'true' }, dir === 'up' ? '▲ ' : dir === 'down' ? '▼ ' : '– '),
    h('span', { class: 'sr-only' }, t(`change.${dir}`) + ' '),
    signedPctPoints(v, digits));
}

export function tile({ label, value, unit, sub, trend, hint }) {
  return h('div', { class: 'tile' },
    h('div', { class: 'tile-label' }, label, hint ? h('span', { class: 'hint', title: hint, 'aria-label': hint, tabindex: '0' }, '?') : null),
    h('div', { class: 'tile-value' }, value, unit ? h('span', { class: 'tile-unit' }, unit) : null),
    sub ? h('div', { class: 'tile-sub' }, sub) : null,
    trend && trend.length > 1 ? sparkline(trend, { width: 120, height: 24, className: 'spark tile-spark' }) : null);
}

export function segmented(label, options, value, onChange) {
  const group = h('div', { class: 'segmented', role: 'radiogroup', 'aria-label': label });
  for (const o of options) {
    const b = h('button', { type: 'button', role: 'radio', 'aria-checked': String(o.value === value), dataset: { v: o.value } }, o.label);
    b.addEventListener('click', () => {
      group.querySelectorAll('button').forEach(x => x.setAttribute('aria-checked', String(x === b)));
      onChange(o.value);
    });
    group.append(b);
  }
  return group;
}

export const moneyShort = v => money(v, { compact: true });
export { compact };
