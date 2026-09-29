import { h, mount, debounce, reducedMotion } from '../dom.js';
import { t, money, num, int, relTime, pct } from '../i18n.js';
import { sparkline, updateSparkline } from '../charts.js';
import * as api from '../api.js';
import { loadMarket } from '../app.js';
import { icon, change } from './common.js';

const SORTS = ['name', 'price', 'movers', 'gainers', 'losers', 'traded'];

export async function render(root, ctx) {
  const { state } = ctx;
  const market = await loadMarket(true);
  const items = market.items.map(i => state.items.get(i.id));
  const cats = market.categories;
  const pro = state.mode === 'pro';

  let query = '';
  let cat = null;
  let sort = pro ? 'movers' : 'name';
  let sortDir = -1;
  const cells = new Map();

  const lead = h('div', { class: 'lead' },
    h('h1', { class: 'sr-only' }, t('market.title')),
    h('p', { class: 'lead-line' },
      market.open ? t('market.openLine', { count: items.length }) : t('market.closedLine'),
      ' ', h('a', { href: '/economy', 'data-link': true }, t('market.cpiLink', { cpi: num(market.cpi, 1) }))));

  const search = h('input', { type: 'search', class: 'search', placeholder: t('market.search'), 'aria-label': t('market.search'), autocomplete: 'off' });
  search.addEventListener('input', debounce(() => { query = search.value.trim().toLowerCase(); draw(); }, 80));

  const chips = h('div', { class: 'chips', role: 'radiogroup', 'aria-label': t('market.categories') });
  const chip = (id, label) => {
    const b = h('button', { type: 'button', role: 'radio', class: 'chip', 'aria-checked': String(cat === id) }, label);
    b.addEventListener('click', () => {
      cat = id;
      chips.querySelectorAll('.chip').forEach(c => c.setAttribute('aria-checked', String(c === b)));
      draw();
    });
    return b;
  };
  chips.append(chip(null, t('market.allCategories')), ...cats.map(c => chip(c.id, c.name)));

  const sortSel = h('select', { class: 'sort', 'aria-label': t('market.sortBy') },
    SORTS.map(s => h('option', { value: s, selected: s === sort }, t(`market.sort.${s}`))));
  sortSel.addEventListener('change', () => { sort = sortSel.value; sortDir = -1; draw(); });

  const filters = h('div', { class: 'filters market-filters' }, search, pro ? null : sortSel, chips);

  const board = h('div', { class: pro ? 'ticker' : 'board' });
  const feed = state.config.pages.tradesFeed ? h('aside', { class: 'feed', 'aria-labelledby': 'feed-title' },
    h('h2', { id: 'feed-title' }, t('market.recentTrades')), h('ol', { class: 'feed-list' })) : null;

  mount(root, h('section', { class: `page market ${pro ? 'is-pro' : ''}` }, lead, filters,
    h('div', { class: 'market-body' }, board, feed)));

  function visible() {
    let list = items.filter(i => (!cat || i.cat === cat) && (!query || i.name.toLowerCase().includes(query) || i.id.includes(query)));
    const key = {
      name: (a, b) => a.name.localeCompare(b.name),
      price: (a, b) => b.price - a.price,
      movers: (a, b) => Math.abs(b.ch1h) - Math.abs(a.ch1h),
      gainers: (a, b) => b.ch1h - a.ch1h,
      losers: (a, b) => a.ch1h - b.ch1h,
      traded: (a, b) => b.ops - a.ops,
      buy: (a, b) => b.buy - a.buy,
      sell: (a, b) => b.sell - a.sell,
      ch1h: (a, b) => b.ch1h - a.ch1h,
      spread: (a, b) => b.spread - a.spread,
      stock: (a, b) => b.stock - a.stock,
      ops: (a, b) => b.ops - a.ops,
    }[sort] || ((a, b) => a.name.localeCompare(b.name));
    list = list.slice().sort(key);
    if (pro && sortDir === 1) list.reverse();
    return list;
  }

  function slot(it) {
    const price = h('span', { class: 'slot-price' }, money(it.price));
    const chg = h('span', { class: 'slot-change' }, change(it.ch1h));
    const spark = sparkline(it.spark || [], { width: 56, height: 16 });
    const el = h('a', { class: 'slot', href: `/item/${encodeURIComponent(it.id)}`, 'data-link': true },
      h('span', { class: 'slot-well' }, icon(it.id, 40)),
      h('span', { class: 'slot-name' }, it.name),
      h('span', { class: 'slot-figures' }, price, chg),
      spark);
    cells.set(it.id, { price, chg, spark, el });
    return el;
  }

  const COLS = [
    { k: 'name', label: t('col.item') },
    { k: 'price', label: t('col.price'), num: true },
    { k: 'buy', label: t('col.buy'), num: true },
    { k: 'sell', label: t('col.sell'), num: true },
    { k: 'ch1h', label: t('col.change1h'), num: true },
    { k: 'spread', label: t('col.spread'), num: true },
    { k: 'stock', label: t('col.stock'), num: true, hint: t('col.stockHint') },
    { k: 'ops', label: t('col.activity'), num: true },
  ];

  function row(it) {
    const price = h('td', { class: 'num' }, money(it.price));
    const buy = h('td', { class: 'num' }, money(it.buy));
    const sell = h('td', { class: 'num' }, money(it.sell));
    const chg = h('td', { class: 'num' }, change(it.ch1h, 2));
    const spark = sparkline(it.spark || [], { width: 72, height: 18 });
    const el = h('tr', { tabindex: '-1', dataset: { id: it.id } },
      h('th', { scope: 'row' }, h('a', { href: `/item/${encodeURIComponent(it.id)}`, 'data-link': true, class: 'row-link' }, icon(it.id, 20), it.name)),
      price, buy, sell, chg,
      h('td', { class: 'num' }, pct(it.spread, 1)),
      h('td', { class: 'num' }, int(it.stock)),
      h('td', { class: 'num' }, int(it.ops)),
      h('td', { class: 'spark-cell' }, spark));
    cells.set(it.id, { price, buy, sell, chg, spark, el });
    return el;
  }

  function draw() {
    cells.clear();
    const list = visible();
    if (!list.length) {
      mount(board, h('p', { class: 'empty' }, t('market.noMatch')));
      return;
    }
    if (!pro) {
      mount(board, list.map(slot));
      return;
    }
    const head = h('tr', null, COLS.map(c => {
      const active = sort === c.k;
      const b = h('button', { type: 'button', class: 'th-btn' }, c.label, active ? (sortDir === -1 ? ' ↓' : ' ↑') : '');
      b.addEventListener('click', () => { sortDir = active ? -sortDir : -1; sort = c.k; draw(); });
      return h('th', { scope: 'col', class: c.num ? 'num' : null, 'aria-sort': active ? (sortDir === -1 ? 'descending' : 'ascending') : 'none', title: c.hint || null }, b);
    }), h('th', { scope: 'col' }, h('span', { class: 'sr-only' }, t('col.trend'))));
    mount(board, h('div', { class: 'table-scroll' }, h('table', { class: 'data ticker-table' },
      h('caption', { class: 'sr-only' }, t('market.tableCaption')),
      h('thead', null, head), h('tbody', null, list.map(row)))),
      h('p', { class: 'kbd-help' }, t('market.keys')));
  }

  draw();

  const offPrices = ctx.onPrices(changed => {
    for (const it of changed) {
      const c = cells.get(it.id);
      if (!c) continue;
      c.price.textContent = money(it.price);
      c.chg.replaceChildren(change(it.ch1h, pro ? 2 : 1));
      if (c.buy) c.buy.textContent = money(it.buy);
      if (c.sell) c.sell.textContent = money(it.sell);
      if (it.spark) updateSparkline(c.spark, it.spark);
      if (!reducedMotion() && it.prev != null && it.prev !== it.price) {
        const cls = it.price > it.prev ? 'tick-up' : 'tick-down';
        c.el.classList.remove('tick-up', 'tick-down');
        void c.el.offsetWidth;
        c.el.classList.add(cls);
      }
    }
  });

  const onKey = e => {
    if (e.target.matches('input, select, textarea')) {
      if (e.key === 'Escape') e.target.blur();
      return;
    }
    if (e.key === '/') { e.preventDefault(); search.focus(); return; }
    if (!pro) return;
    const rows = [...board.querySelectorAll('tbody tr')];
    if (!rows.length) return;
    const i = rows.indexOf(document.activeElement);
    if (e.key === 'j' || e.key === 'ArrowDown') { e.preventDefault(); rows[Math.min(rows.length - 1, i + 1)].focus(); }
    else if (e.key === 'k' || e.key === 'ArrowUp') { e.preventDefault(); rows[Math.max(0, i - 1)].focus(); }
    else if (e.key === 'Enter' && i >= 0) ctx.navigate(`/item/${encodeURIComponent(rows[i].dataset.id)}`);
  };
  document.addEventListener('keydown', onKey);

  let feedTimer = null;
  async function loadFeed() {
    if (!feed) return;
    try {
      const trades = await api.get('/api/trades?limit=20');
      const list = feed.querySelector('.feed-list');
      if (!trades.length) { mount(list, h('li', { class: 'empty' }, t('market.noTrades'))); return; }
      mount(list, trades.map(tr => h('li', null,
        h('a', { href: `/item/${encodeURIComponent(tr.item)}`, 'data-link': true, class: 'feed-item' },
          icon(tr.item, 20),
          h('span', { class: 'feed-text' },
            t(tr.buy ? 'market.feedBought' : 'market.feedSold', { player: tr.player, amount: int(tr.amount), item: tr.name })),
          h('span', { class: 'feed-meta' }, money(tr.value), h('time', { datetime: new Date(tr.t).toISOString() }, relTime(tr.t)))))));
    } catch {  }
  }
  loadFeed();
  if (feed) feedTimer = setInterval(loadFeed, 10000);

  return () => {
    offPrices();
    document.removeEventListener('keydown', onKey);
    clearInterval(feedTimer);
  };
}
