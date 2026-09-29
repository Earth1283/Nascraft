import { h, mount } from '../dom.js';
import { t, money, moneyCompact, num, int, pct } from '../i18n.js';
import { lineChart, barChart, columnChart } from '../charts.js';
import * as api from '../api.js';
import { tile, moneyShort } from './common.js';

const S1 = 'var(--series-1)', S2 = 'var(--series-2)';

export async function render(root) {
  const a = await api.get('/api/analytics');
  const w = a.wealth;

  const kpis = h('div', { class: 'tiles' },
    w ? tile({ label: t('analytics.gini'), value: num(w.gini, 3), sub: t('analytics.holders', { count: w.holders, n: int(w.holders) }), hint: t('hint.gini') }) : null,
    w ? tile({ label: t('analytics.top10'), value: pct(w.top10, 1), sub: t('analytics.top1', { v: pct(w.top1, 1) }) }) : null,
    w ? tile({ label: t('analytics.bottom50'), value: pct(w.bottom50, 1), sub: t('analytics.bottom50Sub') }) : null,
    tile({ label: t('analytics.concentration'), value: num(a.hhi, 3), sub: t(hhiWord(a.hhi)), hint: t('hint.hhi') }),
    tile({ label: t('analytics.traders'), value: int(a.traders), sub: t('analytics.window', { days: a.windowDays }) }));

  const charts = [];
  if (w) {
    const x = w.lorenz.map((_, i) => i / (w.lorenz.length - 1));
    charts.push(lineChart({
      title: t('analytics.lorenz'), note: t('analytics.lorenzNote'), x,
      xLabel: v => pct(v, 0), y: v => pct(v, 0), zero: true,
      series: [
        { name: t('analytics.actual'), values: w.lorenz, color: S1 },
        { name: t('analytics.equality'), values: x, color: 'var(--muted)' },
      ],
    }));
    charts.push(columnChart({
      title: t('analytics.histogram'), note: t('analytics.histogramNote'),
      labels: w.histogram.map(b => `${moneyCompact(b.from)}+`),
      values: w.histogram.map(b => b.count), value: v => int(v), color: S1,
      labelHeader: t('analytics.wealthBand'), valueHeader: t('analytics.players'),
    }));
  }
  charts.push(columnChart({
    title: t('analytics.hourly'), note: t('analytics.hourlyNote', { days: a.windowDays }),
    labels: a.hourly.map((_, i) => String(i).padStart(2, '0')),
    values: a.hourly.map(x => x[0]), value: v => int(v), color: S1, labelEvery: 3,
    labelHeader: t('analytics.hour'), valueHeader: t('analytics.tradesCount'),
  }));
  charts.push(barChart({
    title: t('analytics.itemFlows'), note: t('analytics.itemFlowsNote'), labelHeader: t('col.item'),
    series: [{ name: t('analytics.bought'), color: S1 }, { name: t('analytics.sold'), color: S2 }],
    rows: a.items.map(i => ({ label: i.name, values: [i.bought, i.sold] })), value: v => moneyShort(v),
    empty: t('analytics.noTrades'),
  }));
  if (a.categories.length) charts.push(barChart({
    title: t('analytics.categoryFlows'), labelHeader: t('col.category'),
    series: [{ name: t('analytics.bought'), color: S1 }, { name: t('analytics.sold'), color: S2 }],
    rows: a.categories.map(c => ({ label: c.name, values: [c.bought, c.sold] })), value: v => moneyShort(v),
  }));

  const traders = h('section', { class: 'panel' },
    h('h2', null, t('analytics.topTraders')),
    a.topTraders.length === 0 ? h('p', { class: 'muted' }, t('analytics.noTrades')) :
      h('table', { class: 'data' },
        h('thead', null, h('tr', null, h('th', { scope: 'col' }, '#'), h('th', { scope: 'col' }, t('col.player')),
          h('th', { scope: 'col', class: 'num' }, t('col.volume')), h('th', { scope: 'col', class: 'num' }, t('col.trades')))),
        h('tbody', null, a.topTraders.map((tr, i) => h('tr', null,
          h('td', { class: 'rank' }, i + 1), h('th', { scope: 'row' }, tr.player),
          h('td', { class: 'num' }, money(tr.volume)), h('td', { class: 'num' }, int(tr.trades)))))));

  const volatile = h('section', { class: 'panel' },
    h('h2', null, t('analytics.volatile')),
    h('p', { class: 'muted small' }, t('analytics.volatileNote')),
    h('table', { class: 'data' },
      h('thead', null, h('tr', null, h('th', { scope: 'col' }, t('col.item')), h('th', { scope: 'col', class: 'num' }, t('col.volatility')))),
      h('tbody', null, a.volatile.map(v => h('tr', null,
        h('th', { scope: 'row' }, h('a', { href: `/item/${encodeURIComponent(v.id)}`, 'data-link': true }, v.name)),
        h('td', { class: 'num' }, pct(v.volatility, 2)))))));

  mount(root, h('section', { class: 'page analytics' },
    h('div', { class: 'lead' }, h('h1', null, t('analytics.title')), h('p', { class: 'lead-line' }, t('analytics.lead', { days: a.windowDays }))),
    kpis,
    h('div', { class: 'chart-grid' }, charts),
    h('div', { class: 'two-col' }, traders, volatile)));
}

function hhiWord(v) {
  if (v < 0.15) return 'hhi.low';
  if (v < 0.25) return 'hhi.moderate';
  return 'hhi.high';
}
