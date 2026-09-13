#!/usr/bin/env python3
"""Runs every query the monitoring stack depends on against a live Prometheus and Loki.

A panel whose query returns nothing reads "No data", and an alert whose series does not exist
can never fire. Neither fails anywhere: Grafana renders the empty panel, Prometheus evaluates the
rule as healthy. Both happened — whole dashboards stayed empty for a release because the metrics
they asked for were never exported. This takes every expression from the Railhook dashboards and
from the Prometheus and Loki rule files, resolves the dashboard variables to "everything", and
asks the running stack for the last hour. An expression with no series fails the check unless the
allow-list names it with the reason it is legitimately empty until something happens.

Alert rules are checked without their final comparison — "is outbox_queue_depth there" is the
question, not "is it above 300 right now".

Standard library only, so it runs in a bare python image on the monitoring network:
  make monitoring-check-queries
"""

import argparse
import json
import pathlib
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

REPO = pathlib.Path(__file__).resolve().parent.parent
DASHBOARDS = REPO / "monitoring" / "grafana" / "dashboards"
PROMETHEUS_RULES = [REPO / "monitoring" / "prometheus" / "alerts.yml", REPO / "monitoring" / "prometheus" / "host-alerts.yml"]
LOKI_RULES = [REPO / "monitoring" / "loki" / "rules" / "railhook.yml"]
ALLOW_LIST = pathlib.Path(__file__).resolve().with_suffix(".allow")
# Vendored from grafana.com and written against every node-exporter collector there is; its
# empty panels are collectors this host does not enable, not signals Railhook relies on.
SKIPPED_DASHBOARDS = {"node-exporter-full.json"}

GLOBAL_VARIABLES = {
    "__rate_interval": "5m",
    "__interval": "1m",
    "__range": "1h",
    "__range_s": "3600",
    "__range_ms": "3600000",
    "__interval_ms": "60000",
}


def load_allow_list(extra_files=()):
    allowed = {}
    for path in [ALLOW_LIST, *map(pathlib.Path, extra_files)]:
        if path.exists():
            allowed.update(parse_allow_list(path.read_text()))
    return allowed


def parse_allow_list(text):
    allowed = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        source, title, reason = (part.strip() for part in line.split("|", 2))
        allowed[(source, title)] = reason
    return allowed


def dashboard_variables(dashboard):
    values = {}
    for var in dashboard.get("templating", {}).get("list", []):
        if var.get("type") in ("textbox", "constant"):
            values[var["name"]] = var.get("query", "") if var.get("type") == "constant" else ""
        else:
            values[var["name"]] = var.get("allValue") or ".*"
    return values


def substitute(expr, variables):
    merged = {**GLOBAL_VARIABLES, **variables}

    def replace(match):
        name = match.group("braced") or match.group("bare") or match.group("brackets")
        return merged.get(name, match.group(0))

    return re.sub(
        r"\$\{(?P<braced>[A-Za-z_][A-Za-z0-9_]*)(?::[a-z]+)?\}|\$(?P<bare>[A-Za-z_][A-Za-z0-9_]*)|\[\[(?P<brackets>[A-Za-z_][A-Za-z0-9_]*)\]\]",
        replace,
        expr,
    )


def panels(items):
    for panel in items:
        yield panel
        yield from panels(panel.get("panels", []))


def is_loki(target, panel):
    for source in (target.get("datasource"), panel.get("datasource")):
        if isinstance(source, dict) and (source.get("type") == "loki" or "loki" in str(source.get("uid", ""))):
            return True
    return False


def dashboard_queries():
    for path in sorted(DASHBOARDS.glob("*.json")):
        if path.name in SKIPPED_DASHBOARDS:
            continue
        dashboard = json.loads(path.read_text())
        variables = dashboard_variables(dashboard)
        for panel in panels(dashboard.get("panels", [])):
            for target in panel.get("targets", []) or []:
                expr = target.get("expr")
                if not expr or target.get("hide"):
                    continue
                title = panel.get("title", "")
                if target.get("legendFormat"):
                    title = f'{title} [{target.get("refId", "")}]'
                yield path.name, title, panel.get("title", ""), substitute(expr, variables), "loki" if is_loki(target, panel) else "prometheus"


def top_level_left_of_comparison(expr):
    """The expression an alert compares, without the comparison: `a / b > 0.5` → `a / b`."""
    depth = 0
    last = None
    i = 0
    while i < len(expr):
        ch = expr[i]
        if ch in "([{":
            depth += 1
        elif ch in ")]}":
            depth -= 1
        elif depth == 0 and ch == '"':
            i = expr.index('"', i + 1)
        elif depth == 0:
            for op in (">=", "<=", "==", "!=", ">", "<"):
                if expr.startswith(op, i):
                    last = i
                    i += len(op) - 1
                    break
        i += 1
    return expr[:last].strip() if last is not None else expr.strip()


def rule_queries():
    for path, backend in [(p, "prometheus") for p in PROMETHEUS_RULES] + [(p, "loki") for p in LOKI_RULES]:
        for name, expr in parse_rules(path.read_text()):
            yield path.name, name, name, substitute(top_level_left_of_comparison(" ".join(expr.split())), {}), backend


def parse_rules(text):
    """Enough YAML for rule files: `- alert:`/`- record:` names and their `expr` (inline or `|`)."""
    lines = text.splitlines()
    name = None
    i = 0
    while i < len(lines):
        line = lines[i]
        match = re.match(r"^\s*-\s*(alert|record):\s*(\S+)", line)
        if match:
            name = match.group(2)
        expr_match = re.match(r"^(\s*)expr:\s*(.*)$", line)
        if expr_match and name:
            indent = len(expr_match.group(1))
            value = expr_match.group(2).strip()
            if value in ("|", ">", "|-", ">-"):
                block = []
                i += 1
                while i < len(lines) and (not lines[i].strip() or len(lines[i]) - len(lines[i].lstrip()) > indent):
                    block.append(lines[i].strip())
                    i += 1
                yield name, " ".join(part for part in block if part)
                continue
            yield name, value.strip("'\"")
        i += 1


def get_json(url):
    with urllib.request.urlopen(url, timeout=30) as response:
        return json.loads(response.read())


def has_series(backend, base, expr, window_seconds):
    end = int(time.time())
    start = end - window_seconds
    if backend == "prometheus":
        params = {"query": expr, "start": start, "end": end, "step": 60}
        url = f"{base}/api/v1/query_range?{urllib.parse.urlencode(params)}"
    else:
        params = {"query": expr, "start": f"{start}000000000", "end": f"{end}000000000", "limit": 1, "step": 60}
        url = f"{base}/loki/api/v1/query_range?{urllib.parse.urlencode(params)}"
    body = get_json(url)
    if body.get("status") != "success":
        raise RuntimeError(body.get("error", "query failed"))
    return any(series.get("values") for series in body["data"]["result"])


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--prometheus", default="http://prometheus:9090")
    parser.add_argument("--loki", default="http://loki:3100")
    parser.add_argument("--window", type=int, default=3600, help="seconds of history to look back over")
    parser.add_argument("--allow-file", action="append", default=[],
                        help="an extra allow-list for this environment, e.g. a clone with no public domain or backups")
    args = parser.parse_args()

    allowed = load_allow_list(args.allow_file)
    used_allowances = set()
    counts = {"ok": 0, "empty": 0, "allowed": 0, "error": 0}
    failures = []

    for source, label, panel_title, expr, backend in list(dashboard_queries()) + list(rule_queries()):
        base = args.prometheus if backend == "prometheus" else args.loki
        key = (source, panel_title)
        try:
            present = has_series(backend, base, expr, args.window)
        except (urllib.error.URLError, RuntimeError, ValueError) as error:
            counts["error"] += 1
            failures.append(f"ERROR   {source} | {label} | {error} | {expr}")
            continue
        if present:
            counts["ok"] += 1
            print(f"ok      {source} | {label}")
        elif key in allowed:
            counts["allowed"] += 1
            used_allowances.add(key)
            print(f"allowed {source} | {label} — {allowed[key]}")
        else:
            counts["empty"] += 1
            failures.append(f"EMPTY   {source} | {label} | {expr}")

    for line in failures:
        print(line)
    for key in sorted(set(allowed) - used_allowances):
        print(f"note    allow-list entry matched nothing that was empty: {key[0]} | {key[1]}")
    print(f"\n{counts['ok']} with data, {counts['allowed']} empty and allowed, "
          f"{counts['empty']} empty, {counts['error']} failed to run")
    return 1 if counts["empty"] or counts["error"] else 0


if __name__ == "__main__":
    sys.exit(main())
