// @vitest-environment node
import { afterEach, describe, expect, it } from 'vitest';
import { spawnSync } from 'node:child_process';
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..', '..', '..');
const read = (p: string) => readFileSync(join(repoRoot, p), 'utf8');

/**
 * The monitoring stack is optional, and when it runs on a host facing the internet it must not
 * be the way in.
 *
 * It used to publish Prometheus, Alertmanager and Loki on the host, and shipped Grafana with a
 * password printed in the README, the Makefile and the docs. Anyone who put Grafana behind a
 * domain had a well-known login in front of their logs. These tests hold the stack to: nothing
 * but Grafana published, Grafana only on loopback, no password unless the operator set one, and
 * a total memory budget a small host can afford.
 */

const dirs: string[] = [];
afterEach(() => {
  for (const dir of dirs.splice(0)) rmSync(dir, { recursive: true, force: true });
});
const scratch = (prefix: string) => {
  const dir = mkdtempSync(join(tmpdir(), prefix));
  dirs.push(dir);
  return dir;
};

/** Each service's block in monitoring/docker-compose.yml, by name. */
function composeServices(): Map<string, string> {
  const compose = read('monitoring/docker-compose.yml');
  const section = compose.slice(compose.indexOf('\nservices:\n'), compose.indexOf('\nvolumes:\n'));
  const blocks = new Map<string, string>();
  const re = /^ {2}([a-z0-9-]+):\n([\s\S]*?)(?=^ {2}[a-z0-9-]+:\n|(?![\s\S]))/gm;
  for (const m of section.matchAll(re)) blocks.set(m[1], m[2]);
  return blocks;
}

function megabytes(limit: string): number {
  const m = /^(\d+)([mg])$/i.exec(limit);
  if (!m) throw new Error(`unreadable memory limit ${limit}`);
  return Number(m[1]) * (m[2].toLowerCase() === 'g' ? 1024 : 1);
}

describe('monitoring/docker-compose.yml', () => {
  const services = composeServices();

  it('has the exporters the host dashboards and alerts read', () => {
    for (const name of ['prometheus', 'alertmanager', 'grafana', 'loki', 'promtail', 'node-exporter', 'cadvisor', 'blackbox']) {
      expect(services.has(name), `service ${name}`).toBe(true);
    }
  });

  it('publishes Grafana on loopback and nothing else at all', () => {
    for (const [name, block] of services) {
      const ports = /^ {4}ports:\n((?: {6}(?:#.*|- .*)\n)+)/m.exec(block);
      if (name === 'grafana') {
        expect(ports, 'grafana publishes a port').not.toBeNull();
        const entries = ports![1].split('\n').filter((line) => line.trim().startsWith('- '));
        expect(entries.length).toBeGreaterThan(0);
        for (const line of entries) {
          expect(line, 'grafana binds loopback only').toMatch(/- "127\.0\.0\.1:/);
        }
      } else {
        expect(ports, `${name} must not publish a port`).toBeNull();
      }
    }
  });

  it('pins every image to a version', () => {
    for (const [name, block] of services) {
      const image = /^ {4}image: (\S+)$/m.exec(block)?.[1];
      expect(image, `${name} has an image`).toBeTruthy();
      expect(image, `${name} image is pinned`).toMatch(/:v?\d+\.\d+\.\d+$/);
    }
  });

  it('fits in 1.5 GB of memory limits, every service limited', () => {
    let total = 0;
    for (const [name, block] of services) {
      const limit = /memory: (\S+)/.exec(block)?.[1];
      expect(limit, `${name} has a memory limit`).toBeTruthy();
      total += megabytes(limit!);
    }
    expect(total).toBeLessThanOrEqual(1536);
  });

  it('keeps metrics 15 days and logs 7 by default', () => {
    expect(services.get('prometheus')).toContain('--storage.tsdb.retention.time=${PROMETHEUS_RETENTION:-15d}');
    expect(services.get('loki')).toContain('${LOKI_RETENTION_PERIOD:-168h}');
    expect(read('monitoring/loki/loki-config.yml')).toContain('${LOKI_RETENTION_PERIOD:-168h}');
  });

  it('takes the Grafana password from GRAFANA_ADMIN_PASSWORD with no fallback', () => {
    expect(services.get('grafana')).toMatch(/GF_SECURITY_ADMIN_PASSWORD: \$\{GRAFANA_ADMIN_PASSWORD:-\}$/m);
  });

  it('names its own project and takes the platform network from the environment', () => {
    const compose = read('monitoring/docker-compose.yml');
    expect(compose).toMatch(/^name: railhook-monitoring$/m);
    expect(compose).toMatch(/name: \$\{RAILHOOK_NETWORK:-railhook_webhook-network\}/);
  });

  it("names node-exporter after the host, not its container id, without sharing more of the host", () => {
    // node_uname_info.nodename is what the Host dashboard's selector lists; in a container it is the id.
    expect(services.get('node-exporter')).toMatch(/^ {4}hostname: \$\{MONITORING_NODENAME:-railhook-host\}$/m);
    expect(services.get('node-exporter')).not.toMatch(/uts: host|privileged|cap_add/);
    expect(read('Makefile')).toMatch(/MONITORING_NODENAME=\$\(or \$\(MONITORING_NODENAME\),\$\(shell hostname\)\)/);
  });

  it('runs a cAdvisor that reads Docker 29 containers, with one extra capability and nothing wider', () => {
    const cadvisor = services.get('cadvisor')!;
    const version = /image: ghcr\.io\/google\/cadvisor:v0\.(\d+)\.\d+$/m.exec(cadvisor);
    expect(version, 'cadvisor image').not.toBeNull();
    expect(Number(version![1]), 'v0.54 or newer reads containerd-snapshotter containers').toBeGreaterThanOrEqual(54);
    expect(cadvisor).toContain('- /var/lib/containerd:/var/lib/containerd:ro');
    expect(cadvisor).toMatch(/^ {4}cap_add:\n {6}- SYSLOG\n(?! {6}- )/m);
    expect(cadvisor).not.toMatch(/privileged|uts: host|network_mode: host/);
  });

  it('mounts the host read-only into the exporters', () => {
    expect(services.get('node-exporter')).toContain('- /:/host:ro,rslave');
    expect(services.get('node-exporter')).toContain('--path.rootfs=/host');
    const volumes = /^ {4}volumes:\n((?: {6}- .*\n)+)/m.exec(services.get('cadvisor')!)?.[1] ?? '';
    expect(volumes).not.toBe('');
    for (const line of volumes.trim().split('\n')) {
      expect(line, 'cadvisor mount is read-only').toMatch(/:ro$/);
    }
  });
});

describe('the Containers dashboard', () => {
  type Panel = { title: string; targets?: Array<{ expr: string; refId: string }>; fieldConfig?: { defaults?: { unit?: string } } };
  const panels = (JSON.parse(read('monitoring/grafana/dashboards/railhook-containers.json')).panels as Panel[]);
  const panel = (title: string) => panels.find((p) => p.title === title)!;

  it('shows CPU as a share of the host, not a count of cores rounded to 0.00', () => {
    for (const title of ['CPU, all containers', 'CPU by container']) {
      expect(panel(title).targets![0].expr, title).toMatch(/\/ scalar\(count\(node_cpu_seconds_total\{mode="idle"\}\)\) \* 100$/);
      expect(panel(title).fieldConfig?.defaults?.unit, title).toBe('percent');
    }
  });

  it('lists a recreated container once, and alerts on its restarts once', () => {
    expect(panel('Restarts (15m window)').targets![0].expr).toMatch(/^sum by \(name\) \(changes\(container_start_time_seconds/);
    expect(panel('Memory vs Compose limit').targets![0].expr).toMatch(/^max by \(name\) \(/);
    expect(read('monitoring/prometheus/host-alerts.yml')).toMatch(
      /alert: ContainerRestarting\n\s+expr: \|\n(?:\s+#.*\n)*\s+sum by \(name\) \(changes\(container_start_time_seconds\{name!=""\}\[15m\]\)\) >= 2/,
    );
  });
});

describe('the query check allow-list', () => {
  it('names only panels and rules that exist, each with a reason', () => {
    const titles = new Set<string>();
    for (const file of readdirSync(join(repoRoot, 'monitoring/grafana/dashboards'))) {
      const walk = (items: Array<{ title?: string; panels?: unknown[] }>) =>
        items.forEach((panel) => {
          titles.add(`${file}|${panel.title ?? ''}`);
          walk((panel.panels ?? []) as Array<{ title?: string; panels?: unknown[] }>);
        });
      walk(JSON.parse(read(`monitoring/grafana/dashboards/${file}`)).panels ?? []);
    }
    for (const file of ['prometheus/alerts.yml', 'prometheus/host-alerts.yml', 'loki/rules/railhook.yml']) {
      for (const match of read(`monitoring/${file}`).matchAll(/-\s*(?:alert|record):\s*(\S+)/g)) {
        titles.add(`${file.split('/').pop()}|${match[1]}`);
      }
    }
    const entries = read('scripts/check-monitoring-queries.allow')
      .split('\n')
      .map((line) => line.trim())
      .filter((line) => line && !line.startsWith('#'));
    expect(entries.length).toBeGreaterThan(0);
    for (const entry of entries) {
      const [file, title, reason] = entry.split('|').map((part) => part.trim());
      expect(titles, entry).toContain(`${file}|${title}`);
      expect(reason, `${entry} needs a reason`).toBeTruthy();
    }
  });
});

describe('the default Grafana password', () => {
  const OLD = 'railhook_monitor_2024';

  it('appears nowhere except in the check that refuses it', () => {
    const allowed = new Set(['monitoring/grafana/entrypoint.sh', 'install.sh']);
    const walk = (dir: string): string[] =>
      readdirSync(join(repoRoot, dir)).flatMap((entry) => {
        const path = join(dir, entry);
        return statSync(join(repoRoot, path)).isDirectory() ? walk(path) : [path];
      });
    const files = [
      ...walk('monitoring'),
      ...walk('railhook-docs/src/content/docs'),
      'Makefile',
      '.env.dist',
      'docs/OPERATIONS.md',
    ];
    const offenders = files.filter((f) => !allowed.has(f) && read(f).includes(OLD));
    expect(offenders).toEqual([]);
  });

  it('stops Grafana from starting when it is empty, a known default, or short', () => {
    for (const pw of ['', OLD, 'admin', 'short-password']) {
      const result = spawnSync('sh', [join(repoRoot, 'monitoring/grafana/entrypoint.sh')], {
        encoding: 'utf8',
        env: { PATH: process.env.PATH ?? '/usr/bin:/bin', GF_SECURITY_ADMIN_PASSWORD: pw },
      });
      expect(result.status, `password ${JSON.stringify(pw)}`).toBe(78);
      expect(result.stderr).toMatch(/GRAFANA_ADMIN_PASSWORD/);
    }
  });
});

describe('alertmanager/render-config.sh', () => {
  function render(env: Record<string, string>) {
    const out = join(scratch('railhook-am-'), 'alertmanager.yml');
    const result = spawnSync('sh', [join(repoRoot, 'monitoring/alertmanager/render-config.sh')], {
      encoding: 'utf8',
      env: { PATH: process.env.PATH ?? '/usr/bin:/bin', ALERTMANAGER_CONFIG_OUT: out, ...env },
    });
    expect(result.status, result.stderr).toBe(0);
    return { config: readFileSync(out, 'utf8'), log: `${result.stdout}${result.stderr}` };
  }

  const resend = {
    ALERTMANAGER_EMAIL_TO: 'ops@example.com',
    ALERTMANAGER_EMAIL_FROM: 'noreply@example.com',
    ALERTMANAGER_SMTP_HOST: 'smtp.resend.com',
    ALERTMANAGER_SMTP_PORT: '587',
    ALERTMANAGER_SMTP_USERNAME: 'resend',
    ALERTMANAGER_SMTP_PASSWORD: "re_s3cr'et",
  };

  it('authenticates to a real relay over TLS', () => {
    const { config } = render(resend);
    expect(config).toContain("smarthost: 'smtp.resend.com:587'");
    expect(config).toContain('require_tls: true');
    expect(config).toContain("auth_username: 'resend'");
    expect(config, 'a quote in the password is escaped for YAML').toContain("auth_password: 're_s3cr''et'");
  });

  it('never prints the password or the Telegram token', () => {
    const { log } = render({ ...resend, ALERTMANAGER_TELEGRAM_BOT_TOKEN: '123:tok-en', ALERTMANAGER_TELEGRAM_CHAT_ID: '-100200' });
    expect(log).not.toContain('re_s3cr');
    expect(log).not.toContain('tok-en');
    expect(log).toMatch(/email.*telegram/);
  });

  it('mails through the Railhook template, never linking to the unpublished Alertmanager or Prometheus', () => {
    const { config } = render(resend);
    expect(config).toMatch(/^templates:\n {2}- '\/etc\/alertmanager\/templates\/\*\.tmpl'\n {2}- '.*railhook-links\.tmpl'$/m);
    expect(config).toContain(`Subject: '{{ template "railhook.email.subject" . }}'`);
    expect(config).toContain(`html: '{{ template "railhook.email.html" . }}'`);
    expect(config).toContain(`text: '{{ template "railhook.email.text" . }}'`);
    const template = read('monitoring/alertmanager/email.tmpl');
    expect(template).not.toMatch(/GeneratorURL|ExternalURL/);
  });

  it('puts the summary in the subject as plain text and escapes it only in the HTML body', () => {
    // Alertmanager renders headers with its HTML engine: without safeHtml a ">" in a summary
    // reached the subject line as "&gt;".
    const template = read('monitoring/alertmanager/email.tmpl');
    const block = (name: string) => {
      const start = template.indexOf(`{{ define "${name}" -}}`);
      expect(start, name).toBeGreaterThan(-1);
      return template.slice(start, template.indexOf('{{- end }}', start));
    };
    expect(block('railhook.email.subject')).toMatch(/\.Annotations\.summary[^}]*\| safeHtml/);
    expect(block('railhook.email.html'), 'the HTML body keeps escaping').not.toContain('safeHtml');
    expect(block('railhook.email.text')).not.toContain('safeHtml');
  });

  it('links alert mail to Grafana on MONITORING_DOMAIN, or explains the tunnel without one', () => {
    const links = (env: Record<string, string>) => {
      const { config } = render({ ...resend, ...env });
      const path = /- '([^']*railhook-links\.tmpl)'/.exec(config)?.[1];
      expect(path, 'the config names the links template').toBeTruthy();
      return readFileSync(path!, 'utf8');
    };
    const withDomain = links({ MONITORING_DOMAIN: 'grafana.example.com', RAILHOOK_DOMAIN: 'railhook.example.com' });
    expect(withDomain).toContain('https://grafana.example.com/d/railhook-alerts/alerts');
    expect(withDomain).toContain(' — railhook.example.com');
    expect(withDomain).not.toMatch(/localhost:909[03]/);

    const without = links({});
    expect(without).toContain('ssh -L 3001:127.0.0.1:3001');
    expect(without).not.toMatch(/https?:\/\/[^ ]*grafana/);

    expect(links({ MONITORING_DOMAIN: 'evil.example"}}{{' }), 'not a hostname: no link written').not.toContain('evil');
  });

  it('skips TLS only for a local capture server', () => {
    expect(render({ ...resend, ALERTMANAGER_SMTP_HOST: 'mailpit' }).config).toContain('require_tls: false');
  });

  it('sends the Watchdog only to the heartbeat URL, and never by mail', () => {
    const { config } = render({ ...resend, ALERTMANAGER_HEARTBEAT_URL: 'https://hc-ping.example/uuid' });
    const route = /- match:\n\s+alertname: Watchdog\n\s+receiver: (\S+)/.exec(config);
    expect(route?.[1]).toBe('railhook-heartbeat');
    expect(config.indexOf('alertname: Watchdog'), 'matched before the severity routes').toBeLessThan(
      config.indexOf('severity: critical'),
    );
    const receiver = config.slice(config.indexOf('- name: railhook-heartbeat'));
    expect(receiver).toContain("url: 'https://hc-ping.example/uuid'");
    expect(receiver).not.toContain('email_configs');
    expect(render(resend).config, 'without a URL the receiver is empty, and still valid').toMatch(
      /- name: railhook-heartbeat\n?$/,
    );
  });

  it('adds Telegram only with a token and a numeric chat id', () => {
    const on = render({ ALERTMANAGER_TELEGRAM_BOT_TOKEN: '123:abc', ALERTMANAGER_TELEGRAM_CHAT_ID: '-100200' }).config;
    expect(on.match(/telegram_configs:/g)).toHaveLength(3);
    expect(on).toContain('chat_id: -100200');
    const bad = render({ ALERTMANAGER_TELEGRAM_BOT_TOKEN: '123:abc', ALERTMANAGER_TELEGRAM_CHAT_ID: '@channel' });
    expect(bad.config).not.toContain('telegram_configs');
    expect(bad.log).toMatch(/must be a number/);
  });
});

describe('prometheus/render-targets.sh', () => {
  function render(env: Record<string, string>) {
    const sd = scratch('railhook-sd-');
    const result = spawnSync('sh', [join(repoRoot, 'monitoring/prometheus/render-targets.sh')], {
      encoding: 'utf8',
      env: { PATH: process.env.PATH ?? '/usr/bin:/bin', PROMETHEUS_SD_DIR: sd, ...env },
    });
    expect(result.status, result.stderr).toBe(0);
    const file = (name: string) => readFileSync(join(sd, name), 'utf8');
    return { pub: file('public-http.yml'), http: file('internal-http.yml'), tcp: file('internal-tcp.yml') };
  }

  it('probes the site, the docs and health on the domain by default, and Caddy from inside', () => {
    const t = render({ RAILHOOK_DOMAIN: 'example.com' });
    expect(t.pub).toContain("- 'https://example.com/'");
    expect(t.pub).toContain("- 'https://example.com/docs/'");
    expect(t.pub).toContain("- 'https://example.com/actuator/health'");
    expect(t.http).toContain("- 'http://ui:5173/'");
    expect(t.tcp).toContain("- 'caddy:443'");
  });

  it('takes an explicit list instead, and probes no Caddy without a domain', () => {
    const t = render({ MONITORING_PROBE_URLS: 'https://a.example/,https://a.example/x, not-a-url' });
    expect(t.pub).toContain("- 'https://a.example/x'");
    expect(t.pub).not.toContain('not-a-url');
    expect(t.tcp.trim()).toBe('[]');
  });
});

describe('the Caddyfile install.sh writes', () => {
  function writeCaddyfile(envFile: string | null) {
    const installer = read('install.sh');
    const start = installer.indexOf('write_caddyfile() {');
    // The function ends where its last heredoc does; the Caddyfile inside has braces of its own.
    const end = installer.indexOf('\nCADDY\n}\n', start);
    expect(start, 'write_caddyfile in install.sh').toBeGreaterThan(-1);
    expect(end).toBeGreaterThan(start);
    const dir = scratch('railhook-caddy-');
    if (envFile !== null) writeFileSync(join(dir, '.env'), envFile);
    const script = `set -euo pipefail\nwarn() { echo "$*" >&2; }\n${installer.slice(start, end + '\nCADDY\n}\n'.length)}\nwrite_caddyfile\n`;
    const result = spawnSync('bash', ['-c', script], { encoding: 'utf8', env: { PATH: process.env.PATH ?? '/usr/bin:/bin', INSTALL_DIR: dir } });
    expect(result.status, result.stderr).toBe(0);
    return { caddyfile: readFileSync(join(dir, 'Caddyfile'), 'utf8'), log: result.stderr };
  }

  it('adds Grafana behind MONITORING_DOMAIN, and only then', () => {
    const withIt = writeCaddyfile('RAILHOOK_DOMAIN=example.com\nMONITORING_DOMAIN=grafana.example.com\n').caddyfile;
    expect(withIt).toMatch(/^grafana\.example\.com \{$/m);
    expect(withIt).toContain('reverse_proxy railhook-grafana:3000');
    expect(withIt, 'the platform block is still there').toContain('reverse_proxy ui:5173');

    expect(writeCaddyfile('RAILHOOK_DOMAIN=example.com\n').caddyfile).not.toContain('railhook-grafana');
    expect(writeCaddyfile(null).caddyfile).not.toContain('railhook-grafana');
  });

  it('serves Grafana with a certificate you provide, when both files are named', () => {
    const env = 'RAILHOOK_DOMAIN=example.com\nMONITORING_DOMAIN=grafana.example.com\n';
    const withCert = writeCaddyfile(
      `${env}MONITORING_TLS_CERT=/data/certs/grafana.example.com.pem\nMONITORING_TLS_KEY=/data/certs/grafana.example.com.key\n`,
    ).caddyfile;
    const grafanaBlock = withCert.slice(withCert.indexOf('grafana.example.com {'));
    expect(grafanaBlock).toMatch(/^\ttls \/data\/certs\/grafana\.example\.com\.pem \/data\/certs\/grafana\.example\.com\.key$/m);
    expect(withCert.slice(0, withCert.indexOf('grafana.example.com {')), 'the platform keeps its own certificate').not.toMatch(/^\ttls /m);

    expect(writeCaddyfile(env).caddyfile, 'no files: Caddy obtains one').not.toMatch(/^\ttls /m);
    expect(writeCaddyfile(`${env}MONITORING_TLS_CERT=/data/certs/a.pem\n`).caddyfile, 'a certificate without its key').not.toMatch(/^\ttls /m);
  });

  it('refuses a certificate path that is not a plain absolute path', () => {
    const { caddyfile, log } = writeCaddyfile(
      'MONITORING_DOMAIN=grafana.example.com\nMONITORING_TLS_CERT=/data/a.pem }\nMONITORING_TLS_KEY=/data/a.key\n',
    );
    expect(caddyfile).toContain('railhook-grafana:3000');
    expect(caddyfile).not.toMatch(/^\ttls /m);
    expect(log).toMatch(/MONITORING_TLS_CERT/);
  });

  it('refuses a value that is not a hostname rather than writing it into the config', () => {
    const { caddyfile, log } = writeCaddyfile('MONITORING_DOMAIN=evil.example {\n');
    expect(caddyfile).not.toContain('railhook-grafana');
    expect(log).toMatch(/not a hostname/);
  });
});

describe('railhook monitoring', () => {
  function helperSource(): string {
    const installer = read('install.sh');
    const start = installer.indexOf(`<<'HELPER'\n`);
    const end = installer.indexOf('\nHELPER\n', start);
    return installer.slice(start + `<<'HELPER'\n`.length, end + 1);
  }

  it("hands the stack this server's name unless .env names another", () => {
    expect(helperSource()).toMatch(/MONITORING_NODENAME="\$\(env_value MONITORING_NODENAME \| grep \. \|\| hostname\)"/);
  });

  it('fetches exactly the files monitoring/ holds', () => {
    const listed = /^MONITORING_FILES="\n([\s\S]*?)"$/m.exec(helperSource())?.[1].trim().split('\n').sort();
    const walk = (dir: string): string[] =>
      readdirSync(dir).flatMap((entry) => {
        const path = join(dir, entry);
        return statSync(path).isDirectory() ? walk(path) : [path];
      });
    const root = join(repoRoot, 'monitoring');
    const onDisk = walk(root)
      .map((f) => relative(root, f))
      .filter((f) => f !== 'README.md')
      .sort();
    expect(listed).toEqual(onDisk);
  });

  function install(env: string) {
    const dir = scratch('railhook-mon-');
    const bin = join(dir, 'bin');
    mkdirSync(bin);
    writeFileSync(join(bin, 'docker'), '#!/bin/sh\necho "docker $*" >> "$(dirname "$0")/calls"\nexit 0\n');
    chmodSync(join(bin, 'docker'), 0o755);
    writeFileSync(join(bin, 'curl'), '#!/bin/sh\necho "curl $*" >> "$(dirname "$0")/calls"\nexit 1\n');
    chmodSync(join(bin, 'curl'), 0o755);
    writeFileSync(join(dir, 'railhook'), helperSource());
    chmodSync(join(dir, 'railhook'), 0o755);
    writeFileSync(join(dir, '.env'), env);
    const run = (...args: string[]) => {
      const r = spawnSync('bash', [join(dir, 'railhook'), 'monitoring', ...args], {
        encoding: 'utf8',
        env: { PATH: `${bin}:${process.env.PATH ?? '/usr/bin:/bin'}`, HOME: dir },
      });
      const calls = existsSync(join(bin, 'calls')) ? readFileSync(join(bin, 'calls'), 'utf8') : '';
      return { status: r.status, out: `${r.stdout}${r.stderr}`, calls };
    };
    return { run };
  }

  it('will not start without a Grafana password of its own, and starts nothing', () => {
    for (const env of ['API_IMAGE_TAG=2.20.0\n', 'GRAFANA_ADMIN_PASSWORD=railhook_monitor_2024\n', 'GRAFANA_ADMIN_PASSWORD=tooshort\n']) {
      const { status, out, calls } = install(env).run('up');
      expect(status, env).not.toBe(0);
      expect(out).toMatch(/GRAFANA_ADMIN_PASSWORD/);
      expect(calls, 'no compose up, no download').not.toMatch(/ up |curl/);
    }
  });

  it('says what it does when asked nothing', () => {
    const { status, out } = install('').run();
    expect(status).not.toBe(0);
    expect(out).toMatch(/monitoring up\|down\|status/);
  });
});
