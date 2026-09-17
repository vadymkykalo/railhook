import { describe, it, expect } from 'vitest';
import { formatJson } from '../json';

describe('formatJson', () => {
  it('keeps integers beyond Number.MAX_SAFE_INTEGER digit for digit', () => {
    expect(formatJson('{"id":1234567890123456789}')).toBe('{\n  "id": 1234567890123456789\n}');
  });

  it('keeps high-precision decimals and exponents exactly as written', () => {
    expect(formatJson('[0.10000000000000000001,1E+400,-0.0]')).toBe('[\n  0.10000000000000000001,\n  1E+400,\n  -0.0\n]');
  });

  it('indents nested objects and arrays two spaces per level', () => {
    expect(formatJson('{"a":{"b":[1,{"c":true}],"d":null}}')).toBe(
      '{\n  "a": {\n    "b": [\n      1,\n      {\n        "c": true\n      }\n    ],\n    "d": null\n  }\n}',
    );
  });

  it('leaves braces, commas, colons and escaped quotes inside strings alone', () => {
    const input = '{"s":"a{b}[c],d:\\"e\\"\\\\","t":"\\u00e9"}';
    expect(formatJson(input)).toBe('{\n  "s": "a{b}[c],d:\\"e\\"\\\\",\n  "t": "\\u00e9"\n}');
  });

  it('prints empty containers compactly, as JSON.stringify does', () => {
    expect(formatJson(' { "a" : [ ] , "b" : { } } ')).toBe('{\n  "a": [],\n  "b": {}\n}');
  });

  it('matches JSON.stringify for ordinary JSON', () => {
    const value = { event: 'order.created', amount: 12.5, items: [{ sku: 'x', qty: 2 }], ok: false };
    expect(formatJson(JSON.stringify(value))).toBe(JSON.stringify(value, null, 2));
  });

  it('returns text that is not JSON unchanged', () => {
    expect(formatJson('not json {')).toBe('not json {');
    expect(formatJson('{"a":1')).toBe('{"a":1');
    expect(formatJson('{"a":1} trailing')).toBe('{"a":1} trailing');
    expect(formatJson('')).toBe('');
  });

  it('still stringifies a value that is already an object', () => {
    expect(formatJson({ a: 1 })).toBe('{\n  "a": 1\n}');
    expect(formatJson(null)).toBe('');
  });
});
