// Three failed probes in a row open an incident and the first good one resolves it, so the page
// never says "operational" through an outage nobody posted.

const COMPONENTS = [
  { id: 'website', name: 'Website', url: 'https://railhook.io/', expect: (r) => r.status === 200 },
  {
    id: 'api',
    name: 'API and delivery pipeline',
    url: 'https://railhook.io/actuator/health',
    expect: async (r) => r.status === 200 && (await r.text()).includes('"UP"'),
  },
  { id: 'dashboard', name: 'Dashboard', url: 'https://railhook.io/login', expect: (r) => r.status === 200 },
  { id: 'docs', name: 'Documentation', url: 'https://railhook.io/docs/', expect: (r) => r.status === 200 },
  {
    id: 'mcp',
    name: 'MCP server',
    url: 'https://railhook.io/.well-known/oauth-protected-resource/mcp',
    expect: (r) => r.status === 200,
  },
];

const DOWN_AFTER = 3;
const DAYS = 90;
const TIMEOUT_MS = 10000;

const SCHEMA = [
  `CREATE TABLE IF NOT EXISTS daily (day TEXT NOT NULL, component TEXT NOT NULL, total INTEGER NOT NULL,
     up INTEGER NOT NULL, ms_sum INTEGER NOT NULL, PRIMARY KEY (day, component))`,
  `CREATE TABLE IF NOT EXISTS latest (component TEXT PRIMARY KEY, ok INTEGER NOT NULL, ms INTEGER NOT NULL,
     checked_at INTEGER NOT NULL, fails INTEGER NOT NULL)`,
  `CREATE TABLE IF NOT EXISTS incidents (id INTEGER PRIMARY KEY AUTOINCREMENT, component TEXT, title TEXT NOT NULL,
     body TEXT, started_at INTEGER NOT NULL, resolved_at INTEGER)`,
];

async function ensureSchema(db) {
  await db.batch(SCHEMA.map((sql) => db.prepare(sql)));
}

async function probe(component) {
  const started = Date.now();
  try {
    const response = await fetch(component.url, {
      headers: { 'User-Agent': 'railhook-status/1 (+https://status.railhook.io)' },
      signal: AbortSignal.timeout(TIMEOUT_MS),
      cf: { cacheTtl: 0 },
    });
    const ok = await component.expect(response);
    return { ok, ms: Date.now() - started };
  } catch {
    return { ok: false, ms: Date.now() - started };
  }
}

async function runChecks(env) {
  const db = env.DB;
  await ensureSchema(db);
  const now = Date.now();
  const day = new Date(now).toISOString().slice(0, 10);
  const results = await Promise.all(COMPONENTS.map(probe));
  const previous = new Map(
    (await db.prepare('SELECT component, fails FROM latest').all()).results.map((r) => [r.component, r.fails]),
  );
  const open = new Map(
    (await db.prepare('SELECT id, component FROM incidents WHERE resolved_at IS NULL AND component IS NOT NULL').all())
      .results.map((r) => [r.component, r.id]),
  );

  const statements = [];
  COMPONENTS.forEach((component, i) => {
    const { ok, ms } = results[i];
    const fails = ok ? 0 : (previous.get(component.id) ?? 0) + 1;
    statements.push(
      db.prepare(
        `INSERT INTO daily (day, component, total, up, ms_sum) VALUES (?1, ?2, 1, ?3, ?4)
         ON CONFLICT (day, component) DO UPDATE SET total = total + 1, up = up + ?3, ms_sum = ms_sum + ?4`,
      ).bind(day, component.id, ok ? 1 : 0, ms),
      db.prepare(
        `INSERT INTO latest (component, ok, ms, checked_at, fails) VALUES (?1, ?2, ?3, ?4, ?5)
         ON CONFLICT (component) DO UPDATE SET ok = ?2, ms = ?3, checked_at = ?4, fails = ?5`,
      ).bind(component.id, ok ? 1 : 0, ms, now, fails),
    );
    if (fails === DOWN_AFTER && !open.has(component.id)) {
      statements.push(
        db.prepare('INSERT INTO incidents (component, title, body, started_at) VALUES (?1, ?2, ?3, ?4)').bind(
          component.id,
          `${component.name} is not responding`,
          'Detected automatically: several checks in a row failed. We are looking into it.',
          now - (DOWN_AFTER - 1) * 60000,
        ),
      );
    }
    if (ok && open.has(component.id)) {
      statements.push(db.prepare('UPDATE incidents SET resolved_at = ?1 WHERE id = ?2').bind(now, open.get(component.id)));
    }
  });
  statements.push(db.prepare('DELETE FROM daily WHERE day < ?1').bind(isoDay(now - (DAYS + 5) * 86400000)));
  await db.batch(statements);
}

function isoDay(ms) {
  return new Date(ms).toISOString().slice(0, 10);
}

async function loadStatus(env) {
  const db = env.DB;
  await ensureSchema(db);
  const since = isoDay(Date.now() - (DAYS - 1) * 86400000);
  const [daily, latest, incidents] = await Promise.all([
    db.prepare('SELECT day, component, total, up, ms_sum FROM daily WHERE day >= ?1').bind(since).all(),
    db.prepare('SELECT component, ok, ms, checked_at, fails FROM latest').all(),
    db.prepare(
      'SELECT id, component, title, body, started_at, resolved_at FROM incidents WHERE started_at >= ?1 OR resolved_at IS NULL ORDER BY started_at DESC LIMIT 20',
    ).bind(Date.now() - 30 * 86400000).all(),
  ]);
  const byComponent = new Map(COMPONENTS.map((c) => [c.id, new Map()]));
  for (const row of daily.results) byComponent.get(row.component)?.set(row.day, row);
  const latestBy = new Map(latest.results.map((r) => [r.component, r]));
  const openIds = new Set(incidents.results.filter((i) => !i.resolved_at && i.component).map((i) => i.component));

  const components = COMPONENTS.map((c) => {
    const days = [];
    let total = 0;
    let up = 0;
    for (let i = DAYS - 1; i >= 0; i--) {
      const day = isoDay(Date.now() - i * 86400000);
      const row = byComponent.get(c.id).get(day);
      if (row) {
        total += row.total;
        up += row.up;
      }
      days.push(row ? { day, uptime: row.up / row.total, avgMs: Math.round(row.ms_sum / row.total) } : { day, uptime: null });
    }
    const last = latestBy.get(c.id);
    const state = openIds.has(c.id) ? 'down' : last && !last.ok ? 'degraded' : last ? 'up' : 'unknown';
    return {
      id: c.id,
      name: c.name,
      state,
      uptime: total ? up / total : null,
      latencyMs: last?.ms ?? null,
      checkedAt: last?.checked_at ?? null,
      days,
    };
  });
  const overall = components.some((c) => c.state === 'down')
    ? 'down'
    : components.some((c) => c.state === 'degraded')
      ? 'degraded'
      : 'up';
  return { overall, components, incidents: incidents.results, generatedAt: Date.now() };
}

const ESC = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ESC[c]);

function pct(value) {
  if (value == null) return '—';
  const p = value * 100;
  return `${p >= 99.995 ? '100' : p.toFixed(2)}%`;
}

function barClass(uptime) {
  if (uptime == null) return 'none';
  if (uptime >= 0.999) return 'ok';
  if (uptime >= 0.98) return 'warn';
  return 'bad';
}

function fmtTime(ms) {
  return new Date(ms).toISOString().replace('T', ' ').slice(0, 16) + ' UTC';
}

function duration(from, to) {
  const minutes = Math.max(1, Math.round((to - from) / 60000));
  if (minutes < 60) return `${minutes} min`;
  const hours = Math.floor(minutes / 60);
  return `${hours} h ${minutes % 60} min`;
}

const ICON = `<svg viewBox="0 0 32 32" fill="none" aria-hidden="true"><rect width="32" height="32" rx="7" fill="#1D4BFF"/><path d="M8 7v12c0 3.9 3.1 7 7 7s7-3.1 7-7v-6" stroke="#fff" stroke-width="2.5" stroke-linecap="round"/><path d="M19 16l3-3 3 3" stroke="#fff" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"/><circle cx="8" cy="7" r="2" fill="#fff"/></svg>`;

const HEADLINE = {
  up: 'All systems operational',
  degraded: 'Some checks are failing',
  down: 'Partial outage',
};

const STATE_LABEL = { up: 'Operational', degraded: 'Degraded', down: 'Outage', unknown: 'No data yet' };

function renderPage(status) {
  const components = status.components
    .map((c) => {
      const bars = c.days
        .map(
          (d) =>
            `<i class="${barClass(d.uptime)}" title="${esc(d.day)}: ${d.uptime == null ? 'no data' : pct(d.uptime)}"></i>`,
        )
        .join('');
      return `<section class="component">
  <div class="row">
    <h3>${esc(c.name)}</h3>
    <span class="state ${c.state}"><span class="dot"></span>${STATE_LABEL[c.state]}</span>
  </div>
  <div class="bars" role="img" aria-label="${esc(c.name)} uptime over ${DAYS} days: ${pct(c.uptime)}">${bars}</div>
  <div class="legend"><span><span class="lg">${DAYS} days ago</span><span class="sm">30 days ago</span></span><span class="uptime">${pct(c.uptime)} uptime${c.latencyMs != null ? ` · ${c.latencyMs} ms now` : ''}</span><span>Today</span></div>
</section>`;
    })
    .join('\n');

  const incidents = status.incidents.length
    ? status.incidents
        .map(
          (i) => `<article class="incident ${i.resolved_at ? 'resolved' : 'open'}">
  <div class="row"><h3>${esc(i.title)}</h3><span class="tag">${i.resolved_at ? 'Resolved' : 'Investigating'}</span></div>
  <p>${esc(i.body)}</p>
  <p class="meta">${fmtTime(i.started_at)}${i.resolved_at ? ` · lasted ${duration(i.started_at, i.resolved_at)}` : ''}</p>
</article>`,
        )
        .join('\n')
    : '<p class="empty">No incidents in the last 30 days.</p>';

  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Railhook Status</title>
<meta name="description" content="Live status and ${DAYS}-day uptime of Railhook: website, API and delivery pipeline, dashboard, docs and MCP server.">
<link rel="canonical" href="https://status.railhook.io/">
<link rel="icon" href="https://railhook.io/favicon.svg" type="image/svg+xml">
<meta http-equiv="refresh" content="60">
<link rel="preconnect" href="https://fonts.googleapis.com">
<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
<link href="https://fonts.googleapis.com/css2?family=Manrope:wght@700;800&family=Onest:wght@400;500;600&display=swap" rel="stylesheet">
<style>
:root{--bg:#fff;--fg:#0B0E1A;--muted:#5A6072;--rail:#E4E6EE;--card:#fff;--primary:#1D4BFF;--ok:#16A34A;--warn:#D97706;--bad:#DC2626;--none:#E4E6EE;--soft:#F5F7FF}
@media (prefers-color-scheme:dark){:root{--bg:#0B0D14;--fg:#EDEFF5;--muted:#9AA3B5;--rail:#232838;--card:#10131C;--primary:#6F8DFF;--ok:#22C55E;--warn:#F59E0B;--bad:#F87171;--none:#232838;--soft:#141A2E}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);font:16px/1.55 Onest,system-ui,sans-serif;-webkit-font-smoothing:antialiased}
a{color:var(--primary);text-decoration:none}a:hover{text-decoration:underline}
.wrap{max-width:52rem;margin:0 auto;padding:0 16px}
header{border-bottom:1px solid var(--rail)}
header .wrap{display:flex;align-items:center;justify-content:space-between;height:60px}
.brand{display:flex;align-items:center;gap:10px;color:var(--fg);font-weight:600}
.brand svg{width:28px;height:28px}
.brand small{color:var(--muted);font-weight:500;margin-left:2px}
header nav{display:flex;gap:18px;font-size:14px}header nav a{color:var(--muted)}header nav a:hover{color:var(--fg);text-decoration:none}
h1{font-family:Manrope,sans-serif;font-weight:800;letter-spacing:-.03em;font-size:clamp(1.9rem,5vw,2.6rem);line-height:1.1;margin:0}
h2{font-family:Manrope,sans-serif;font-weight:700;letter-spacing:-.02em;font-size:1.3rem;margin:48px 0 16px}
h3{font-size:15px;font-weight:600;margin:0}
.hero{padding:48px 0 8px}
.banner{margin-top:24px;display:flex;align-items:center;gap:12px;border-radius:16px;padding:18px 20px;border:1px solid var(--rail);background:var(--soft);font-weight:600}
.banner .dot{width:12px;height:12px}
.banner.up .dot{background:var(--ok);box-shadow:0 0 0 6px color-mix(in srgb,var(--ok) 18%,transparent)}
.banner.degraded .dot{background:var(--warn)}.banner.down .dot{background:var(--bad)}
.banner small{margin-left:auto;color:var(--muted);font-weight:400;font-size:13px}
.dot{display:inline-block;width:8px;height:8px;border-radius:50%;background:var(--none)}
.card{border:1px solid var(--rail);border-radius:16px;background:var(--card);overflow:hidden}
.component{padding:18px 20px}.component+.component{border-top:1px solid var(--rail)}
.row{display:flex;align-items:center;justify-content:space-between;gap:12px}
.state{display:inline-flex;align-items:center;gap:7px;font-size:13px;color:var(--muted)}
.state.up{color:var(--ok)}.state.up .dot{background:var(--ok)}
.state.degraded{color:var(--warn)}.state.degraded .dot{background:var(--warn)}
.state.down{color:var(--bad)}.state.down .dot{background:var(--bad)}
.bars{display:flex;gap:2px;height:34px;margin-top:14px}
.bars i{flex:1;border-radius:2px;background:var(--none);min-width:2px}
.bars i.ok{background:var(--ok)}.bars i.warn{background:var(--warn)}.bars i.bad{background:var(--bad)}
.bars i:hover{opacity:.7}
.legend{display:flex;justify-content:space-between;margin-top:8px;font-size:12px;color:var(--muted);font-family:'JetBrains Mono',ui-monospace,monospace}
.legend .uptime{color:var(--fg)}.legend .sm{display:none}
.incident{border:1px solid var(--rail);border-radius:14px;padding:16px 18px;margin-bottom:12px;background:var(--card)}
.incident p{margin:8px 0 0;color:var(--muted);font-size:14px}.incident .meta{font-size:12px}
.tag{font-size:12px;border-radius:999px;padding:2px 10px;background:var(--soft);color:var(--muted)}
.incident.open .tag{background:color-mix(in srgb,var(--bad) 14%,transparent);color:var(--bad)}
.incident.resolved .tag{color:var(--ok)}
.empty{color:var(--muted)}
footer{margin:56px 0 40px;padding-top:20px;border-top:1px solid var(--rail);font-size:13px;color:var(--muted);display:flex;flex-wrap:wrap;gap:8px 20px;justify-content:space-between}
@media (max-width:640px){.bars i:nth-child(-n+60){display:none}.legend .lg{display:none}.legend .sm{display:inline}header nav a.hide-sm{display:none}}
</style>
</head>
<body>
<header><div class="wrap">
  <a class="brand" href="https://railhook.io/">${ICON}Railhook <small>Status</small></a>
  <nav><a href="https://railhook.io/" class="hide-sm">Website</a><a href="https://railhook.io/docs/">Docs</a><a href="mailto:support@railhook.io">Contact</a></nav>
</div></header>
<main class="wrap">
  <div class="hero">
    <h1>Railhook status</h1>
    <div class="banner ${status.overall}"><span class="dot"></span>${HEADLINE[status.overall]}<small>Checked every minute from outside our servers</small></div>
  </div>
  <h2>Components</h2>
  <div class="card">
${components}
  </div>
  <h2>Incidents</h2>
${incidents}
  <footer><span>Updated ${fmtTime(status.generatedAt)} · refreshes every minute</span><span><a href="/api/status.json">JSON</a> · <a href="https://railhook.io/">railhook.io</a></span></footer>
</main>
</body>
</html>`;
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname === '/api/status.json') {
      const status = await loadStatus(env);
      return Response.json(
        { overall: status.overall, components: status.components.map(({ days, ...c }) => c), incidents: status.incidents },
        { headers: { 'Cache-Control': 'public, max-age=30', 'Access-Control-Allow-Origin': '*' } },
      );
    }
    if (url.pathname === '/robots.txt') {
      return new Response('User-agent: *\nAllow: /\n', { headers: { 'Content-Type': 'text/plain' } });
    }
    if (url.pathname !== '/') return Response.redirect(`${url.origin}/`, 302);
    const status = await loadStatus(env);
    return new Response(renderPage(status), {
      headers: {
        'Content-Type': 'text/html; charset=utf-8',
        'Cache-Control': 'public, max-age=30',
        'Content-Security-Policy':
          "default-src 'none'; style-src 'unsafe-inline' https://fonts.googleapis.com; font-src https://fonts.gstatic.com; img-src https://railhook.io; frame-ancestors 'none'; base-uri 'none'",
        'X-Content-Type-Options': 'nosniff',
        'Referrer-Policy': 'strict-origin-when-cross-origin',
      },
    });
  },

  async scheduled(_event, env, ctx) {
    ctx.waitUntil(runChecks(env));
  },
};
