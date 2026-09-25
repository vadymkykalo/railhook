import { Railhook } from '@railhook/node';
// eslint-disable-next-line @typescript-eslint/no-var-requires
const pkg = require('../../package.json');

describe('package identity (@railhook/node)', () => {
  it('is published under the @railhook/node package name', () => {
    expect(pkg.name).toBe('@railhook/node');
  });

  it('smoke: importing "@railhook/node" constructs a working client', () => {
    const client = new Railhook({ apiKey: 'test_api_key' });
    expect(client).toBeInstanceOf(Railhook);
    expect(client.events).toBeDefined();
  });
});
