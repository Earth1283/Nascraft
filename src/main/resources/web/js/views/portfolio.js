import { h, mount } from '../dom.js';
import { t, money, int, pct, relTime } from '../i18n.js';
import * as api from '../api.js';
import { tile } from './common.js';
import { icon } from './common.js';
import { errorText } from '../app.js';

export async function render(root, ctx) {
  const me = await ctx.refreshMe();
  if (!me) { ctx.navigate('/login', { replace: true }); return; }

  const signOut = h('button', { type: 'button', class: 'btn btn-quiet' }, t('portfolio.signOut'));
  signOut.addEventListener('click', async () => {
    try { await api.post('/api/auth/logout'); } catch (e) { alert(errorText(e)); return; }
    ctx.state.me = null;
    await ctx.refreshMe();
    ctx.navigate('/');
  });

  const holdings = me.portfolio.items;
  mount(root, h('section', { class: 'page portfolio' },
    h('div', { class: 'lead split' },
      h('div', null,
        h('p', { class: 'muted' }, t('portfolio.netWorthOf', { name: me.name })),
        h('h1', { class: 'hero-figure' }, money(me.netWorth))),
      signOut),
    h('div', { class: 'tiles' },
      tile({ label: t('portfolio.balance'), value: money(me.balance), sub: me.online ? t('portfolio.online') : t('portfolio.offline') }),
      tile({ label: t('portfolio.value'), value: money(me.portfolio.value), sub: t('portfolio.slots', { used: holdings.length, capacity: me.portfolio.capacity }) }),
      tile({ label: t('portfolio.debt'), value: money(me.debt), sub: t('portfolio.rate', { v: pct(me.loanRate, 3) }) })),
    h('div', { class: 'two-col' },
      h('section', { class: 'panel' },
        h('h2', null, t('portfolio.holdings')),
        holdings.length === 0 ? h('p', { class: 'muted' }, t('portfolio.emptyHoldings')) :
          h('table', { class: 'data' },
            h('thead', null, h('tr', null, h('th', { scope: 'col' }, t('col.item')), h('th', { scope: 'col', class: 'num' }, t('col.amount')), h('th', { scope: 'col', class: 'num' }, t('col.value')))),
            h('tbody', null, holdings.map(i => h('tr', null,
              h('th', { scope: 'row' }, h('a', { href: `/item/${encodeURIComponent(i.id)}`, 'data-link': true, class: 'row-link' }, icon(i.id, 20), i.name)),
              h('td', { class: 'num' }, int(i.amount)), h('td', { class: 'num' }, money(i.value))))))),
      h('section', { class: 'panel' },
        h('h2', null, t('portfolio.recentTrades')),
        me.trades.length === 0 ? h('p', { class: 'muted' }, t('portfolio.noTrades')) :
          h('ul', { class: 'feed-list' }, me.trades.map(tr => h('li', null,
            h('span', { class: 'feed-item' }, icon(tr.item, 20),
              h('span', { class: 'feed-text' }, t(tr.buy ? 'portfolio.youBought' : 'portfolio.youSold', { amount: int(tr.amount), item: tr.name })),
              h('span', { class: 'feed-meta' }, money(tr.value), h('time', null, relTime(tr.t)))))))))));
}
