import { h, mount, clear } from './dom.js';
import { t, pickLanguage, loadLanguage, setCurrency, LANGUAGE_NAMES } from './i18n.js';
import * as api from './api.js';

const views = {
  market: () => import('./views/market.js'),
  item: () => import('./views/item.js'),
  economy: () => import('./views/economy.js'),
  analytics: () => import('./views/analytics.js'),
  leaderboard: () => import('./views/leaderboard.js'),
  portfolio: () => import('./views/portfolio.js'),
  login: () => import('./views/login.js'),
};

const store = (key, value) => {
  try {
    if (value === undefined) return localStorage.getItem(key);
    localStorage.setItem(key, value);
  } catch {  }
  return null;
};

export const state = {
  config: null,
  me: null,
  market: null,
  items: new Map(),
  mode: 'regular',
  priceListeners: new Set(),
  economyListeners: new Set(),
};

function applyTheme(theme) {
  const root = document.documentElement;
  if (theme === 'light' || theme === 'dark') root.dataset.theme = theme;
  else delete root.dataset.theme;
}

function currentTheme() { return store('nc.theme') || state.config.defaultTheme; }

function setMode(mode) {
  state.mode = mode;
  document.documentElement.dataset.mode = mode;
  if (!state.config.lockMode) store('nc.mode', mode);
}

function parseRoute(path) {
  const p = path.replace(/\/+$/, '') || '/';
  if (p === '/') return { view: 'market' };
  const m = p.match(/^\/item\/([^/]+)$/);
  if (m) return { view: 'item', id: decodeURIComponent(m[1]) };
  const name = p.slice(1);
  return views[name] && name !== 'item' ? { view: name } : { view: 'notfound' };
}

let cleanup = null;
let renderToken = 0;

export function navigate(path, { replace = false } = {}) {
  if (replace) history.replaceState(null, '', path);
  else history.pushState(null, '', path);
  render();
}

async function render() {
  const token = ++renderToken;
  const route = parseRoute(location.pathname);
  const main = document.getElementById('view');
  if (cleanup) { try { cleanup(); } catch {  } cleanup = null; }

  const pages = state.config.pages;
  const allowed = {
    market: pages.market, item: pages.market, economy: pages.economy, analytics: pages.analytics,
    leaderboard: pages.leaderboard, portfolio: true, login: true,
  };
  updateNav(route.view);

  if (route.view === 'notfound' || !allowed[route.view]) {
    mount(main, h('section', { class: 'page narrow' },
      h('h1', null, t('notFound.title')),
      h('p', null, t('notFound.body')),
      h('a', { href: '/', 'data-link': true, class: 'btn' }, t('notFound.back'))));
    return;
  }

  main.setAttribute('aria-busy', 'true');
  try {
    const mod = await views[route.view]();
    if (token !== renderToken) return;
    clear(main);
    cleanup = await mod.render(main, { route, state, navigate, onPrices, onEconomy, refreshMe }) || null;
  } catch (e) {
    if (token !== renderToken) return;
    mount(main, h('section', { class: 'page narrow' },
      h('h1', null, t('error.title')), h('p', null, errorText(e)),
      h('button', { class: 'btn', type: 'button', onclick: () => render() }, t('error.retry'))));
  } finally {
    if (token === renderToken) main.removeAttribute('aria-busy');
  }
  if (token === renderToken) {
    const h1 = main.querySelector('h1');
    document.title = h1 && route.view !== 'market' ? `${h1.textContent} – ${state.config.title}` : state.config.title;
  }
}

export function errorText(e) {
  const code = e && e.code ? e.code : 'network';
  const key = `error.${code}`;
  const msg = t(key);
  return msg === key ? t('error.generic') : msg;
}

function onPrices(fn) { state.priceListeners.add(fn); return () => state.priceListeners.delete(fn); }
function onEconomy(fn) { state.economyListeners.add(fn); return () => state.economyListeners.delete(fn); }

function applyPrices(frame) {
  const changed = [];
  for (const [id, [price, buy, sell, ch1h]] of Object.entries(frame.p || {})) {
    const it = state.items.get(id);
    if (!it) continue;
    if (it.price !== price || it.buy !== buy || it.sell !== sell) {
      it.prev = it.price;
      Object.assign(it, { price, buy, sell, ch1h });
      if (it.spark && it.spark.length) it.spark[it.spark.length - 1] = price;
      changed.push(it);
    }
  }
  if (changed.length) for (const fn of state.priceListeners) fn(changed);
}

export async function loadMarket(force = false) {
  if (state.market && !force) return state.market;
  const m = await api.get('/api/market');
  state.market = m;
  for (const it of m.items) {
    const existing = state.items.get(it.id);
    if (existing) Object.assign(existing, it); else state.items.set(it.id, it);
  }
  return m;
}

export async function refreshMe() {
  try {
    const me = await api.get('/api/me');
    state.me = me.signedIn ? me : null;
  } catch { state.me = null; }
  updateAccount();
  return state.me;
}

const NAV = [
  { view: 'market', href: '/', key: 'nav.market', page: 'market' },
  { view: 'economy', href: '/economy', key: 'nav.economy', page: 'economy' },
  { view: 'analytics', href: '/analytics', key: 'nav.analytics', page: 'analytics' },
  { view: 'leaderboard', href: '/leaderboard', key: 'nav.leaderboard', page: 'leaderboard' },
];

function updateNav(view) {
  const active = view === 'item' ? 'market' : view;
  document.querySelectorAll('.nav a').forEach(a => {
    if (a.dataset.view === active) a.setAttribute('aria-current', 'page'); else a.removeAttribute('aria-current');
  });
}

function updateAccount() {
  const slot = document.getElementById('account');
  if (!slot) return;
  mount(slot, state.me
    ? h('a', { href: '/portfolio', 'data-link': true, class: 'account' },
        h('span', { class: 'avatar', 'aria-hidden': 'true' }, (state.me.name || '?').slice(0, 1).toUpperCase()),
        h('span', null, state.me.name))
    : h('a', { href: '/login', 'data-link': true, class: 'btn btn-quiet' }, t('nav.signIn')));
}

function themeButton() {
  const order = ['system', 'light', 'dark'];
  const btn = h('button', { type: 'button', class: 'icon-btn', id: 'theme-btn' });
  const label = () => {
    const cur = currentTheme();
    btn.textContent = { system: '◐', light: '○', dark: '●' }[cur];
    btn.setAttribute('aria-label', t('theme.label', { theme: t(`theme.${cur}`) }));
    btn.title = t(`theme.${cur}`);
  };
  btn.addEventListener('click', () => {
    const next = order[(order.indexOf(currentTheme()) + 1) % order.length];
    store('nc.theme', next);
    applyTheme(next);
    label();
  });
  label();
  return btn;
}

function modeSwitch() {
  if (state.config.lockMode) return null;
  const make = m => h('button', {
    type: 'button', 'aria-pressed': String(state.mode === m), dataset: { mode: m },
    onclick: () => {
      setMode(m);
      document.querySelectorAll('.mode-switch button').forEach(b => b.setAttribute('aria-pressed', String(b.dataset.mode === m)));
      render();
    },
  }, t(`mode.${m}`));
  return h('div', { class: 'mode-switch', role: 'group', 'aria-label': t('mode.label') }, make('regular'), make('pro'));
}

function languagePicker(lang) {
  const langs = state.config.languages;
  if (langs.length < 2) return null;
  const sel = h('select', { class: 'lang', 'aria-label': t('language.label') },
    langs.map(l => h('option', { value: l, selected: l === lang }, LANGUAGE_NAMES[l] || l)));
  sel.addEventListener('change', async () => {
    await loadLanguage(sel.value);
    buildChrome(sel.value);
    render();
  });
  return sel;
}

function buildChrome(lang) {
  const cfg = state.config;
  const header = document.getElementById('masthead');
  mount(header,
    h('a', { href: '/', 'data-link': true, class: 'wordmark' }, h('span', { class: 'mark', 'aria-hidden': 'true' }), cfg.title),
    h('nav', { class: 'nav', 'aria-label': t('nav.label') },
      NAV.filter(n => cfg.pages[n.page]).map(n => h('a', { href: n.href, 'data-link': true, dataset: { view: n.view } }, t(n.key)))),
    h('div', { class: 'controls' }, modeSwitch(), themeButton(), languagePicker(lang), h('div', { id: 'account' })));
  const skip = document.querySelector('.skip');
  if (skip) skip.textContent = t('nav.skip');
  updateAccount();
}

async function boot() {
  state.config = await api.get('/api/config');
  setCurrency(state.config.currency);
  const lang = pickLanguage(state.config.languages, state.config.defaultLanguage);
  await loadLanguage(lang);

  applyTheme(currentTheme());
  const savedMode = state.config.lockMode ? null : store('nc.mode');
  setMode(savedMode === 'pro' || savedMode === 'regular' ? savedMode : state.config.defaultMode);

  buildChrome(lang);

  document.addEventListener('click', e => {
    const a = e.target.closest('a[data-link]');
    if (!a || e.metaKey || e.ctrlKey || e.shiftKey || e.button !== 0) return;
    e.preventDefault();
    if (a.getAttribute('href') !== location.pathname) navigate(a.getAttribute('href'));
  });
  window.addEventListener('popstate', render);

  const me = refreshMe();
  if (state.config.pages.market || state.config.pages.economy) {
    api.live(frame => applyPrices(frame), eco => { for (const fn of state.economyListeners) fn(eco); });
  }
  await me;
  await render();
}

boot().catch(e => {
  mount(document.getElementById('view'), h('section', { class: 'page narrow' },
    h('h1', null, 'Nascraft'), h('p', null, `The market couldn't load (${e.code || e.message}). Refresh to try again.`)));
});
