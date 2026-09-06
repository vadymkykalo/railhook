#!/usr/bin/env python3
"""Merge the parallel workstreams' i18n hand-off files into the locale bundles.

Several workstreams add translation keys at once. They cannot each edit
en.json/uk.json — concurrent read-modify-write would silently drop whichever
write landed first — so each writes .claude/tasks/i18n-<name>.json in the shape

    {"en": {"ns": {"key": "..."}}, "uk": {"ns": {"key": "..."}}}

and this merges them. It refuses on a key that two workstreams define
differently, and on a key present in one locale and not the other, because
src/i18n/__tests__/locales.test.ts fails CI on exactly that asymmetry.
"""
import json
import sys
from collections import OrderedDict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
HANDOFF_DIR = ROOT / '.claude' / 'tasks'
LOCALES = ROOT / 'railhook-ui' / 'src' / 'i18n' / 'locales'


def flatten(d, prefix=''):
    out = {}
    for k, v in d.items():
        key = f'{prefix}.{k}' if prefix else k
        if isinstance(v, dict):
            out.update(flatten(v, key))
        else:
            out[key] = v
    return out


def deep_merge(base, incoming, origin, claims, errors):
    for k, v in incoming.items():
        if isinstance(v, dict):
            node = base.setdefault(k, OrderedDict())
            if not isinstance(node, dict):
                errors.append(f'{origin}: "{k}" is a value in the bundle but a group in the hand-off')
                continue
            deep_merge(node, v, origin, claims.setdefault(k, {}), errors)
        else:
            prior = claims.get(k)
            if prior and prior[1] != v:
                errors.append(f'{origin}: "{k}" conflicts with {prior[0]}, which set a different value')
                continue
            claims[k] = (origin, v)
            base[k] = v


def sort_deep(d):
    return OrderedDict(
        (k, sort_deep(v) if isinstance(v, dict) else v) for k, v in sorted(d.items())
    )


def main():
    handoffs = sorted(HANDOFF_DIR.glob('i18n-*.json'))
    if not handoffs:
        print('no hand-off files found')
        return 0

    bundles = {
        loc: json.loads((LOCALES / f'{loc}.json').read_text(), object_pairs_hook=OrderedDict)
        for loc in ('en', 'uk')
    }
    claims = {loc: {} for loc in bundles}
    errors = []

    for path in handoffs:
        try:
            data = json.loads(path.read_text(), object_pairs_hook=OrderedDict)
        except json.JSONDecodeError as exc:
            errors.append(f'{path.name}: not valid JSON — {exc}')
            continue

        missing = {'en', 'uk'} - set(data)
        if missing:
            errors.append(f'{path.name}: missing the {", ".join(sorted(missing))} side')
            continue

        en_keys, uk_keys = set(flatten(data['en'])), set(flatten(data['uk']))
        for key in sorted(en_keys - uk_keys):
            errors.append(f'{path.name}: "{key}" has no uk translation')
        for key in sorted(uk_keys - en_keys):
            errors.append(f'{path.name}: "{key}" has no en translation')

        for loc in ('en', 'uk'):
            deep_merge(bundles[loc], data[loc], path.name, claims[loc], errors)

        print(f'{path.name}: {len(en_keys)} keys')

    if errors:
        print('\nREFUSED — fix these first:', file=sys.stderr)
        for e in errors:
            print(f'  {e}', file=sys.stderr)
        return 1

    for loc, bundle in bundles.items():
        (LOCALES / f'{loc}.json').write_text(
            json.dumps(sort_deep(bundle), ensure_ascii=False, indent=2) + '\n'
        )

    en_total, uk_total = len(flatten(bundles['en'])), len(flatten(bundles['uk']))
    print(f'\nmerged into en.json ({en_total} keys) and uk.json ({uk_total} keys)')
    if en_total != uk_total:
        print('locales are asymmetric — locales.test.ts will fail', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
