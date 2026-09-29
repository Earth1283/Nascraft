import { h, mount } from '../dom.js';
import { t } from '../i18n.js';
import * as api from '../api.js';
import { errorText } from '../app.js';

export async function render(root, ctx) {
  if (ctx.state.me) { ctx.navigate('/portfolio', { replace: true }); return; }
  const cmd = ctx.state.config.loginCommand;

  const input = h('input', {
    id: 'code', name: 'code', class: 'code-input', autocomplete: 'one-time-code', autocapitalize: 'characters',
    spellcheck: 'false', inputmode: 'text', maxlength: 40, required: true, placeholder: 'ABCD-EFGH',
  });
  const error = h('p', { class: 'form-error', role: 'alert' });
  const submit = h('button', { type: 'submit', class: 'btn btn-primary' }, t('login.submit'));

  const form = h('form', { class: 'login-form', novalidate: true },
    h('label', { for: 'code' }, t('login.codeLabel')), input, error, submit);
  form.addEventListener('submit', async e => {
    e.preventDefault();
    error.textContent = '';
    const code = input.value.trim();
    if (!code) { error.textContent = t('login.empty'); input.focus(); return; }
    submit.disabled = true;
    try {
      await api.post('/api/auth/login', { code });
      await ctx.refreshMe();
      ctx.navigate('/portfolio', { replace: true });
    } catch (err) {
      error.textContent = errorText(err);
      input.select();
    } finally {
      submit.disabled = false;
    }
  });

  mount(root, h('section', { class: 'page narrow login' },
    h('h1', null, t('login.title')),
    h('ol', { class: 'steps' },
      h('li', null, t('login.step1'), ' ', h('kbd', null, cmd)),
      h('li', null, t('login.step2')),
      h('li', null, t('login.step3'))),
    form,
    h('p', { class: 'muted small' }, t('login.privacy'))));
  input.focus();
}
