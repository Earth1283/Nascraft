try {
  const root = document.documentElement;
  const saved = localStorage.getItem('nc.theme') || root.dataset.themeDefault;
  if (saved === 'light' || saved === 'dark') root.dataset.theme = saved;
} catch {  }
