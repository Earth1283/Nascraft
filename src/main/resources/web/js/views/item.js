import { h, mount } from '../dom.js';
import { t, money, num, int, pct, dateTime } from '../i18n.js';
import { lineChart } from '../charts.js';
import * as api from '../api.js';
import { errorText, loadMarket } from '../app.js';
import { icon, change, segmented } from './common.js';

const SPANS = ['hour', 'day', 'month', 'year', 'all'];
const SERIES = 'var(--series-1)';

export async function render(root, ctx) {
  const { state, route } = ctx;
  const id = route.id;
  const [item] = await Promise.all([api.get(`/api/item/${encodeURIComponent(id)}`), loadMarket().catch(() => null)]);
  let span = 'hour';

  const priceEl = h('span', { class: 'hero-figure' }, money(item.price));
  const changeEl = h('span', { class: 'hero-change' }, change(item.ch1h, 2), ' ', h('span', { class: 'muted' }, t('item.pastHour')));
  const buyEl = h('dd', null, money(item.buy));
  const sellEl = h('dd', null, money(item.sell));

  const chartHost = h('div', { class: 'chart-host' });
  const spanPicker = segmented(t('item.span'), SPANS.map(s => ({ value: s, label: t(`span.${s}`) })), span, v => { span = v; loadChart(); });

  async function loadChart() {
    chartHost.classList.add('refetching');
    try {
      const hist = await api.get(`/api/item/${encodeURIComponent(id)}/history?span=${span}`);
      mount(chartHost, lineChart({
        title: t('item.priceHistory'),
        note: t(`span.note.${span}`),
        x: hist.t,
        series: [{ name: t('item.price'), values: hist.p, color: SERIES }],
        y: v => money(v),
        xLabel: v => dateTime(v, span),
        area: true,
        height: 260,
        empty: t('item.noHistory'),
      }));
    } catch (e) {
      mount(chartHost, h('p', { class: 'empty' }, errorText(e)));
    } finally {
      chartHost.classList.remove('refetching');
    }
  }

  const stat = (label, value, hint) => h('div', { class: 'stat' },
    h('dt', null, label, hint ? h('span', { class: 'hint', title: hint, 'aria-label': hint, tabindex: '0' }, '?') : null), value);

  const stats = h('dl', { class: 'stats' },
    stat(t('item.buyPrice'), buyEl, t('item.buyPriceHint')),
    stat(t('item.sellPrice'), sellEl, t('item.sellPriceHint')),
    stat(t('item.equilibrium'), h('dd', null, money(item.equilibrium)), t('item.equilibriumHint')),
    stat(t('item.stock'), h('dd', null, int(item.stock)), t('item.stockHint')),
    stat(t('item.allTimeHigh'), h('dd', null, money(item.high))),
    stat(t('item.allTimeLow'), h('dd', null, money(item.low))),
    stat(t('item.taxes'), h('dd', null, t('item.taxesValue', { buy: pct(item.buyTax, 2), sell: pct(item.sellTax, 2) })), t('item.taxesHint')),
    item.volatilitySpread > 0 ? stat(t('item.volatilitySpread'), h('dd', null, pct(item.volatilitySpread, 2)), t('item.volatilitySpreadHint')) : null,
    stat(t('item.elasticity'), h('dd', null, num(item.elasticity, 2)), t('item.elasticityHint')),
    stat(t('item.activity'), h('dd', null, int(item.ops)), t('item.activityHint')));

  const related = [];
  if (item.recipe) {
    related.push(h('section', { class: 'related' },
      h('h2', null, t('item.craftedFrom')),
      h('p', { class: 'muted small' }, t('item.recipeNote', { count: item.recipe.output })),
      h('ul', { class: 'chip-list' }, item.recipe.ingredients.map(i =>
        h('li', null, h('a', { href: `/item/${encodeURIComponent(i.id)}`, 'data-link': true, class: 'item-chip' },
          icon(i.id, 20), `${num(i.amount, 2)} × ${state.items.get(i.id)?.name || i.id}`))))));
  }
  if (item.links && item.links.length) {
    related.push(h('section', { class: 'related' },
      h('h2', null, t('item.movesWith')),
      h('p', { class: 'muted small' }, t('item.movesWithNote')),
      h('ul', { class: 'chip-list' }, item.links.map(l =>
        h('li', null, h('a', { href: `/item/${encodeURIComponent(l.id)}`, 'data-link': true, class: 'item-chip' },
          icon(l.id, 20), state.items.get(l.id)?.name || l.id,
          h('span', { class: 'muted' }, l.strength >= 0 ? ` +${pct(l.strength, 0)}` : ` ${pct(l.strength, 0)}`)))))));
  }
  if (item.children && item.children.length) {
    related.push(h('section', { class: 'related' },
      h('h2', null, t('item.variants')),
      h('ul', { class: 'plain' }, item.children.map(c => h('li', null, t('item.variantLine', { name: c.name, mult: num(c.multiplier, 2) }))))));
  }

  const trade = tradePanel(item, state, ctx);

  mount(root, h('section', { class: 'page item' },
    h('a', { href: '/', 'data-link': true, class: 'back' }, t('item.back')),
    h('div', { class: 'item-grid' },
      h('div', { class: 'item-main' },
        h('header', { class: 'item-head' },
          h('span', { class: 'slot-well big' }, icon(item.id, 64)),
          h('div', null,
            h('p', { class: 'muted' }, item.catName || ''),
            h('h1', null, item.name),
            h('p', { class: 'hero' }, priceEl, changeEl))),
        h('div', { class: 'filters' }, spanPicker),
        chartHost,
        stats,
        related),
      trade)));

  loadChart();

  const off = ctx.onPrices(changed => {
    const it = changed.find(c => c.id === id);
    if (!it) return;
    priceEl.textContent = money(it.price);
    changeEl.firstChild.replaceWith(change(it.ch1h, 2));
    buyEl.textContent = money(it.buy);
    sellEl.textContent = money(it.sell);
    trade.update?.(it);
  });
  return off;
}

function tradePanel(item, state, ctx) {
  const cfg = state.config;
  const panel = h('aside', { class: 'trade', 'aria-labelledby': 'trade-title' });
  if (!cfg.trading) return panel;

  if (!state.me) {
    mount(panel,
      h('h2', { id: 'trade-title' }, t('trade.title')),
      h('p', null, t('trade.signInFirst', { command: cfg.loginCommand })),
      h('a', { href: '/login', 'data-link': true, class: 'btn' }, t('nav.signIn')));
    return panel;
  }

  let amount = 1;
  let last = { buy: item.buy, sell: item.sell };
  const held = () => state.me.portfolio.items.find(i => i.id === item.id)?.amount || 0;

  const input = h('input', { type: 'number', min: 1, max: cfg.maxTradeAmount, value: amount, inputmode: 'numeric', id: 'trade-amount', class: 'amount' });
  const quoteBuy = h('strong');
  const quoteSell = h('strong');
  const holding = h('span');
  const balance = h('span');
  const status = h('p', { class: 'trade-status', role: 'status', 'aria-live': 'polite' });
  const buyBtn = h('button', { type: 'button', class: 'btn btn-primary' });
  const sellBtn = h('button', { type: 'button', class: 'btn' });

  const refresh = () => {
    amount = Math.max(1, Math.min(cfg.maxTradeAmount, parseInt(input.value, 10) || 1));
    quoteBuy.textContent = money(last.buy * amount);
    quoteSell.textContent = money(last.sell * amount);
    buyBtn.textContent = t('trade.buy', { count: amount, amount: int(amount) });
    sellBtn.textContent = t('trade.sell', { count: amount, amount: int(amount) });
    holding.textContent = t('trade.holding', { amount: int(held()) });
    balance.textContent = t('trade.balance', { balance: money(state.me.balance) });
    sellBtn.disabled = held() < amount;
  };

  const quick = [1, 16, 64].map(n => h('button', { type: 'button', class: 'chip', onclick: () => { input.value = n; refresh(); } }, int(n)));
  input.addEventListener('input', refresh);

  async function submit(side) {
    buyBtn.disabled = sellBtn.disabled = true;
    status.textContent = t('trade.working');
    try {
      const r = await api.post('/api/trade', { item: item.id, side, amount });
      state.me.balance = r.balance;
      const entry = state.me.portfolio.items.find(i => i.id === item.id);
      if (entry) entry.amount = r.holding; else state.me.portfolio.items.push({ id: item.id, name: item.name, amount: r.holding, value: 0 });
      status.textContent = t(side === 'buy' ? 'trade.bought' : 'trade.sold', { amount: int(amount), item: item.name, worth: money(r.worth) });
    } catch (e) {
      status.textContent = errorText(e);
    } finally {
      buyBtn.disabled = false;
      refresh();
    }
  }
  buyBtn.addEventListener('click', () => submit('buy'));
  sellBtn.addEventListener('click', () => submit('sell'));

  mount(panel,
    h('h2', { id: 'trade-title' }, t('trade.title')),
    h('p', { class: 'muted small' }, t('trade.explain')),
    h('label', { for: 'trade-amount' }, t('trade.amount')),
    h('div', { class: 'amount-row' }, input, quick),
    h('dl', { class: 'quote' },
      h('dt', null, t('trade.youPay')), h('dd', null, quoteBuy),
      h('dt', null, t('trade.youGet')), h('dd', null, quoteSell)),
    h('p', { class: 'muted small' }, t('trade.estimate')),
    h('div', { class: 'trade-actions' }, buyBtn, sellBtn),
    status,
    h('p', { class: 'trade-foot muted small' }, holding, h('br'), balance));
  refresh();
  panel.update = it => { last = { buy: it.buy, sell: it.sell }; refresh(); };
  return panel;
}
