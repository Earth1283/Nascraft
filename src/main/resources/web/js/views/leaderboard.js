import { h, mount } from '../dom.js';
import { t, money } from '../i18n.js';
import * as api from '../api.js';

export async function render(root) {
  const rows = await api.get('/api/leaderboard');
  const top = rows[0]?.wealth || 1;
  mount(root, h('section', { class: 'page leaderboard narrow' },
    h('div', { class: 'lead' }, h('h1', null, t('leaderboard.title')), h('p', { class: 'lead-line' }, t('leaderboard.lead'))),
    rows.length === 0 ? h('p', { class: 'empty' }, t('leaderboard.empty')) :
      h('ol', { class: 'ranking' }, rows.map((r, i) => h('li', null,
        h('span', { class: 'rank' }, i + 1),
        h('span', { class: 'rank-name' }, r.player),
        h('span', { class: 'rank-bar', 'aria-hidden': 'true' }, h('span', { style: { width: `${Math.max(1, (r.wealth / top) * 100)}%` } })),
        h('span', { class: 'rank-value' }, money(r.wealth, { compact: true })))))));
}
