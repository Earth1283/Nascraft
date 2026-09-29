let dict = {};
let fallback = {};
let locale = 'en';
let currency = { format: '[AMOUNT]', decimals: 2 };
const nf = new Map();

const BCP47 = { en: 'en', es: 'es', zh_CN: 'zh-CN', de: 'de', fr: 'fr', pt_BR: 'pt-BR', ru: 'ru' };
const LANGUAGE_NAMES = {
  en: 'English', es: 'Español', zh_CN: '简体中文', de: 'Deutsch', fr: 'Français', pt_BR: 'Português (Brasil)', ru: 'Русский',
};

export function languageName(code) {
  if (LANGUAGE_NAMES[code]) return LANGUAGE_NAMES[code];
  const tag = code.replace('_', '-');
  try { return new Intl.DisplayNames([tag], { type: 'language' }).of(tag) || code; } catch { return code; }
}

export function pickLanguage(available, def) {
  let saved = null;
  try { saved = localStorage.getItem('nc.lang'); } catch {  }
  if (saved && available.includes(saved)) return saved;
  for (const pref of navigator.languages || [navigator.language]) {
    const p = pref.replace('-', '_');
    const exact = available.find(a => a.toLowerCase() === p.toLowerCase());
    if (exact) return exact;
    const base = available.find(a => a.split('_')[0] === p.split('_')[0].toLowerCase());
    if (base) return base;
  }
  return available.includes(def) ? def : available[0];
}

export async function loadLanguage(lang) {
  const get = l => fetch(`/locales/${l}.json`).then(r => (r.ok ? r.json() : {})).catch(() => ({}));
  const [d, f] = await Promise.all([get(lang), lang === 'en' ? Promise.resolve(null) : get('en')]);
  dict = d;
  fallback = f || d;
  locale = BCP47[lang] || lang.replace('_', '-');
  nf.clear();
  document.documentElement.lang = locale;
  try { localStorage.setItem('nc.lang', lang); } catch {  }
}

export function setCurrency(c) { if (c && c.format) currency = c; }

export function t(key, vars) {
  let str = dict[key] ?? fallback[key];
  if (vars && typeof vars.count === 'number') {
    const rule = new Intl.PluralRules(locale).select(vars.count);
    str = dict[`${key}.${rule}`] ?? dict[`${key}.other`] ?? fallback[`${key}.${rule}`] ?? fallback[`${key}.other`] ?? str;
  }
  if (str == null) return key;
  return vars ? str.replace(/\{(\w+)\}/g, (_, k) => (vars[k] ?? `{${k}}`)) : str;
}

function fmt(opts) {
  const k = JSON.stringify(opts);
  if (!nf.has(k)) nf.set(k, new Intl.NumberFormat(locale, opts));
  return nf.get(k);
}

export const num = (v, digits = 2) => (v == null ? '–' : fmt({ maximumFractionDigits: digits }).format(v));
export const int = v => (v == null ? '–' : fmt({ maximumFractionDigits: 0 }).format(v));
export const compact = v => (v == null ? '–' : fmt({ notation: 'compact', maximumFractionDigits: 1 }).format(v));

export function money(v, { compact: short = false } = {}) {
  if (v == null || !isFinite(v)) return '–';
  const maxDecimals = currency.decimals ?? 2;
  const digits = Math.abs(v) >= 1 ? Math.min(2, maxDecimals) : maxDecimals;
  const n = short && Math.abs(v) >= 10000
    ? fmt({ notation: 'compact', maximumFractionDigits: 1 }).format(v)
    : fmt({ minimumFractionDigits: Math.min(2, digits), maximumFractionDigits: digits }).format(v);
  return currency.format.includes('[AMOUNT]') ? currency.format.replace('[AMOUNT]', n) : n + currency.format;
}

export function moneyCompact(v) {
  if (v == null || !isFinite(v)) return '–';
  const n = fmt({ notation: 'compact', maximumFractionDigits: 1 }).format(v);
  return currency.format.includes('[AMOUNT]') ? currency.format.replace('[AMOUNT]', n) : n + currency.format;
}

export const pct = (v, digits = 2) => (v == null || !isFinite(v) ? '–' : fmt({ style: 'percent', maximumFractionDigits: digits, minimumFractionDigits: 0 }).format(v));

export const signedPctPoints = (v, digits = 1) => (v == null || !isFinite(v) ? '–' : fmt({ signDisplay: 'exceptZero', maximumFractionDigits: digits, minimumFractionDigits: digits }).format(v) + '%');

export function relTime(ts) {
  const diff = (ts - Date.now()) / 1000;
  const rtf = new Intl.RelativeTimeFormat(locale, { numeric: 'auto' });
  const a = Math.abs(diff);
  if (a < 60) return rtf.format(Math.round(diff), 'second');
  if (a < 3600) return rtf.format(Math.round(diff / 60), 'minute');
  if (a < 86400) return rtf.format(Math.round(diff / 3600), 'hour');
  return rtf.format(Math.round(diff / 86400), 'day');
}

export function dateTime(ts, span) {
  const o = span === 'hour' || span === '1d' || span === 'day'
    ? { hour: '2-digit', minute: '2-digit' }
    : span === 'all' || span === 'year' ? { year: 'numeric', month: 'short', day: 'numeric' } : { month: 'short', day: 'numeric', hour: '2-digit' };
  return new Intl.DateTimeFormat(locale, o).format(new Date(ts));
}

export const currentLocale = () => locale;
