import { h, mount } from '../dom.js';
import { t, money, num, pct, relTime, dateTime } from '../i18n.js';
import { lineChart, barChart } from '../charts.js';
import * as api from '../api.js';
import { tile, segmented, moneyShort } from './common.js';

const RANGES = ['1d', '7d', '30d', '90d', 'all'];
const S1 = 'var(--series-1)', S2 = 'var(--series-2)', S3 = 'var(--series-3)';

export async function render(root, ctx) {
  let range = '7d';
  let [eco, hist] = await Promise.all([api.get('/api/economy'), api.get(`/api/economy/history?range=${range}`)]);

  const leadHost = h('div');
  const tilesHost = h('div', { class: 'tiles' });
  const chartsHost = h('div', { class: 'chart-grid' });
  const eventsHost = h('div');

  const rangePicker = segmented(t('economy.range'), RANGES.map(r => ({ value: r, label: t(`range.${r}`) })), range, async v => {
    range = v;
    chartsHost.classList.add('refetching');
    tilesHost.classList.add('refetching');
    try { hist = await api.get(`/api/economy/history?range=${range}`); draw(); }
    finally { chartsHost.classList.remove('refetching'); tilesHost.classList.remove('refetching'); }
  });

  mount(root, h('section', { class: 'page economy' },
    leadHost,
    h('div', { class: 'filters' }, rangePicker, h('span', { class: 'muted small' }, t('economy.updated', { when: relTime(eco.ts) }))),
    tilesHost, eventsHost, chartsHost,
    explainer(eco)));

  function draw() {
    const phase = eco.phase;
    const gap = eco.outputGap;
    mount(leadHost, h('div', { class: 'phase-lead' },
      h('p', { class: 'muted' }, t('economy.phaseIntro')),
      h('h1', { class: `phase phase-${phase}` }, t(`phase.${phase}`)),
      h('p', { class: 'phase-line' },
        Math.abs(gap) < 0.01 ? t('phase.steady.line') : t(`phase.${phase}.line`, { gap: pct(Math.abs(gap), 0) }),
        eco.recession ? h('strong', { class: 'badge badge-critical' }, h('span', { 'aria-hidden': 'true' }, '! '), t('economy.recession')) : null)));

    const series = key => hist[key] || [];
    const f = eco.features;
    mount(tilesHost,
      tile({ label: t('economy.inflation'), value: pct(eco.inflation, 3), unit: t('unit.perDaySuffix'),
        sub: t('economy.target', { v: pct(eco.targets.inflation, 2) }), trend: series('inflation'), hint: t('hint.inflation') }),
      f.centralBank ? tile({ label: t('economy.rate'), value: pct(eco.loanRate, 3), unit: t('unit.perDaySuffix'),
        sub: t(`economy.rateMode.${f.rateMode}`), trend: series('policyRate'), hint: t('hint.rate') }) : null,
      tile({ label: t('economy.gdp'), value: moneyShort(eco.gdp), sub: t('economy.gdpSub', { trades: num(eco.trades, 0) }),
        trend: series('gdp'), hint: t('hint.gdp') }),
      tile({ label: t('economy.moneySupply'), value: moneyShort(eco.moneySupply),
        sub: t('economy.velocity', { v: num(eco.velocity, 3) }), trend: series('moneySupply'), hint: t('hint.moneySupply') }),
      f.treasury ? tile({ label: t('economy.treasury'), value: moneyShort(eco.treasury),
        sub: f.ubi && eco.ubi > 0 ? t('economy.dividend', { v: money(eco.ubi) }) : t('economy.reserve', { v: moneyShort(eco.targets.reserve) }),
        trend: series('treasury'), hint: t('hint.treasury') }) : null,
      f.priceLevel ? tile({ label: t('economy.priceLevel'), value: `×${num(eco.priceLevel, 3)}`,
        sub: t('economy.priceLevelSub'), trend: series('priceLevel'), hint: t('hint.priceLevel') }) : null,
      tile({ label: t('economy.gini'), value: num(eco.gini, 3), sub: t(giniWord(eco.gini)), trend: series('gini'), hint: t('hint.gini') }),
      tile({ label: t('economy.cpi'), value: num(eco.cpi, 1), sub: t('economy.cpiSub'), trend: series('cpi'), hint: t('hint.cpi') }));

    mount(eventsHost, events(eco));

    const x = hist.t;
    const xl = v => dateTime(v, range);
    const charts = [
      lineChart({ title: t('chart.cpi'), note: t('chart.cpiNote'), x, xLabel: xl, y: v => num(v, 1),
        series: [{ name: t('economy.cpi'), values: hist.cpi, color: S1 }], area: true,
        reference: { value: 100, label: t('chart.baseline') } }),
      lineChart({ title: t('chart.inflationRate'), note: t('chart.perDay'), x, xLabel: xl, y: v => pct(v, 2),
        series: [
          { name: t('economy.inflation'), values: hist.inflation, color: S1 },
          ...(f.centralBank ? [{ name: t('economy.rate'), values: hist.policyRate, color: S2 }] : []),
        ],
        reference: { value: eco.targets.inflation, label: t('chart.target') } }),
      lineChart({ title: t('chart.output'), note: t('chart.outputNote'), x, xLabel: xl, y: v => moneyShort(v), zero: true,
        series: [
          { name: t('economy.gdp'), values: hist.gdp, color: S1 },
          { name: t('economy.realGdp'), values: hist.realGdp, color: S2 },
        ] }),
      lineChart({ title: t('chart.moneyFlows'), note: t('chart.moneyFlowsNote'), x, xLabel: xl, y: v => moneyShort(v), zero: true,
        series: [
          { name: t('economy.created'), values: hist.moneyCreated, color: S1 },
          { name: t('economy.destroyed'), values: hist.moneyDestroyed, color: S2 },
        ] }),
      lineChart({ title: t('chart.moneySupply'), x, xLabel: xl, y: v => moneyShort(v),
        series: [{ name: t('economy.moneySupply'), values: hist.moneySupply, color: S1 }], area: true }),
      f.treasury ? lineChart({ title: t('chart.treasury'), x, xLabel: xl, y: v => moneyShort(v), zero: true,
        series: [{ name: t('economy.treasury'), values: hist.treasury, color: S1 }], area: true,
        reference: { value: eco.targets.reserve, label: t('chart.reserve') } }) : null,
      lineChart({ title: t('chart.levers'), note: t('chart.leversNote'), x, xLabel: xl, y: v => `×${num(v, 3)}`,
        series: [
          { name: t('economy.liquidity'), values: hist.liquidity, color: S1 },
          { name: t('economy.taxScale'), values: hist.taxScale, color: S2 },
          { name: t('economy.priceLevel'), values: hist.priceLevel, color: S3 },
        ],
        reference: { value: 1, label: t('chart.neutral') } }),
      eco.sectors.length ? barChart({ title: t('chart.sectors'), note: t('chart.sectorsNote'), labelHeader: t('col.category'),
        series: [{ name: t('chart.sectorIndex'), color: S1 }],
        rows: eco.sectors.slice().sort((a, b) => b.index - a.index).map(s => ({ label: s.name, values: [s.index] })),
        value: v => num(v, 1),
        diverging: { base: 100, up: 'var(--div-up)', down: 'var(--div-down)' } }) : null,
    ];
    mount(chartsHost, charts);
  }

  draw();

  const off = ctx.onEconomy(next => { eco = next; draw(); });
  return off;
}

function giniWord(g) {
  if (g < 0.3) return 'gini.low';
  if (g < 0.5) return 'gini.moderate';
  if (g < 0.7) return 'gini.high';
  return 'gini.extreme';
}

function events(eco) {
  if (!eco.features.shocks) return null;
  const list = eco.shocks;
  return h('section', { class: 'events', 'aria-labelledby': 'events-title' },
    h('h2', { id: 'events-title' }, t('economy.events')),
    list.length === 0
      ? h('p', { class: 'muted' }, t('economy.noEvents'))
      : h('ul', { class: 'event-list' }, list.map(ev => {
          const rising = ev.impact >= 0;
          return h('li', { class: `event ${rising ? 'up' : 'down'}` },
            h('span', { class: 'event-mark', 'aria-hidden': 'true' }, rising ? '▲' : '▼'),
            h('div', null,
              h('strong', null, t(`shock.${ev.kind}`, { category: ev.categoryName || ev.category || '' })),
              h('p', { class: 'muted small' }, t(rising ? 'shock.lineUp' : 'shock.lineDown',
                { pct: pct(Math.abs(ev.impact), 0), ends: relTime(ev.end) }))));
        })));
}

function explainer(eco) {
  const f = eco.features;
  const rows = [
    ['explain.cpi', true], ['explain.inflation', true], ['explain.rate', f.centralBank], ['explain.liquidity', f.liquidity],
    ['explain.priceLevel', f.priceLevel], ['explain.treasury', f.treasury], ['explain.stabilizers', f.stabilizers],
    ['explain.ubi', f.ubi], ['explain.wealthTax', f.wealthTax], ['explain.reversion', f.meanReversion],
    ['explain.spillovers', f.spillovers], ['explain.recipes', f.recipes], ['explain.spread', f.dynamicSpread], ['explain.shocks', f.shocks],
  ].filter(r => r[1]);
  return h('details', { class: 'explainer' },
    h('summary', null, t('explain.title')),
    h('dl', null, rows.map(([k]) => [h('dt', null, t(`${k}.term`)), h('dd', null, t(`${k}.body`))])));
}
