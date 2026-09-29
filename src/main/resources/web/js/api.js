export class ApiError extends Error {
  constructor(status, code) { super(code); this.status = status; this.code = code; }
}

export async function get(path) {
  const r = await fetch(path, { credentials: 'same-origin' });
  if (!r.ok) throw new ApiError(r.status, await code(r));
  return r.json();
}

export async function post(path, body) {
  const r = await fetch(path, {
    method: 'POST',
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'nascraft' },
    body: JSON.stringify(body ?? {}),
  });
  if (r.status === 204) return null;
  if (!r.ok) throw new ApiError(r.status, await code(r));
  return r.json();
}

async function code(r) {
  try { return (await r.json()).error || `http_${r.status}`; } catch { return `http_${r.status}`; }
}

export function live(onPrices, onEconomy) {
  const es = new EventSource('/api/live');
  es.addEventListener('prices', e => { try { onPrices(JSON.parse(e.data)); } catch {  } });
  es.addEventListener('economy', e => { try { onEconomy(JSON.parse(e.data)); } catch {  } });
  return es;
}
