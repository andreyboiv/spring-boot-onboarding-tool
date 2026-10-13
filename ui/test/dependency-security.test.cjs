const assert = require('node:assert/strict');
const { createRequire } = require('node:module');
const { test } = require('node:test');
const { runInNewContext } = require('node:vm');

// Resolve each dependency from its real consumer, including nested installations.
const requireFromAjv = createRequire(require.resolve('ajv/package.json'));
const requireFromSockjs = createRequire(require.resolve('sockjs/package.json'));
const requireFromCopyPlugin = createRequire(require.resolve('copy-webpack-plugin/package.json'));
const requireFromCli = createRequire(require.resolve('@angular/cli/package.json'));
const semver = requireFromCli('semver');
const uri = requireFromAjv('fast-uri');
const uuid = requireFromSockjs('uuid');
const serialize = requireFromCopyPlugin('serialize-javascript');
const lock = require('../package-lock.json');

for (const [name, safeRange, consumerRequire] of [
  ['fast-uri', '>=2.4.6 <3 || >=3.1.7 <4 || >=4.1.4', requireFromAjv],
  ['uuid', '>=11.1.1 <12 || >=12.0.1 <13 || >=13.0.1', requireFromSockjs],
  ['serialize-javascript', '>=7.1.2', requireFromCopyPlugin]
]) {
  test(`${name}: every locked copy and the consumer use a patched version`, () => {
    const copies = Object.entries(lock.packages)
      .filter(([path]) => path.endsWith(`/node_modules/${name}`) || path === `node_modules/${name}`);
    assert.ok(copies.length > 0, `${name} is missing from the lockfile`);
    for (const [path, metadata] of copies) {
      assert.ok(semver.satisfies(metadata.version, safeRange), `${path}: ${metadata.version}`);
    }
    const installedVersion = consumerRequire(`${name}/package.json`).version;
    assert.ok(semver.satisfies(installedVersion, safeRange), `Installed ${name}: ${installedVersion}`);
  });
}

// GHSA-58mr-gqgx-xq4g: reject malformed authority brackets.
for (const address of ['http://[127.0.0.1/', 'http://[not-ipv6]/', 'http://foo[bar]/']) {
  test(`fast-uri rejects ${address}`, () => {
    assert.ok(uri.parse(address).error, 'A malformed host must report an error');
  });
}

for (const [address, host] of [
  ['https://example.com/path', 'example.com'],
  ['http://[::1]/', '::1'],
  ['http://[::ffff:127.0.0.1]/', '::ffff:127.0.0.1']
]) {
  test(`fast-uri still accepts ${address}`, () => {
    const parsed = uri.parse(address);
    assert.equal(parsed.error, undefined);
    assert.equal(parsed.host, host);
  });
}

test('Ajv still resolves schema references through fast-uri', () => {
  const Ajv = require('ajv');
  const ajv = new Ajv();
  ajv.addSchema({ $id: 'https://schemas.example.test/name.json', type: 'string' });
  const validate = ajv.compile({
    $id: 'https://schemas.example.test/employee.json',
    type: 'object',
    properties: { name: { $ref: 'name.json' } },
    required: ['name']
  });
  assert.equal(validate({ name: 'Alice' }), true);
  assert.equal(validate({ name: 42 }), false);
});

const generators = [
  ['v3', (buffer, offset) => uuid.v3('www.example.com', uuid.v3.DNS, buffer, offset), 3],
  ['v5', (buffer, offset) => uuid.v5('www.example.com', uuid.v5.DNS, buffer, offset), 5],
  ['v6', (buffer, offset) => uuid.v6({
    msecs: 1700000000000,
    nsecs: 0,
    clockseq: 1,
    node: [1, 2, 3, 4, 5, 6]
  }, buffer, offset), 6]
];

// GHSA-w5hq-g745-h8pq: invalid writes must throw without partially changing the buffer.
for (const [name, generate, version] of generators) {
  for (const [size, offset] of [[8, 4], [16, -1], [16, 1], [16, 16]]) {
    test(`uuid ${name} rejects buffer size ${size}, offset ${offset} without writes`, () => {
      const buffer = new Uint8Array(size).fill(0xaa);
      const original = buffer.slice();
      assert.throws(() => generate(buffer, offset), RangeError);
      assert.deepEqual(buffer, original);
    });
  }

  test(`uuid ${name} still writes complete identifiers within buffer bounds`, () => {
    for (const [size, offset] of [[16, 0], [24, 4]]) {
      const buffer = new Uint8Array(size).fill(0xaa);
      assert.equal(generate(buffer, offset), buffer);
      assert.deepEqual(buffer.slice(0, offset), new Uint8Array(offset).fill(0xaa));
      assert.deepEqual(buffer.slice(offset + 16), new Uint8Array(size - offset - 16).fill(0xaa));
      const value = uuid.stringify(buffer.slice(offset, offset + 16));
      assert.equal(uuid.validate(value), true);
      assert.equal(uuid.version(value), version);
      if (name === 'v3') assert.equal(value, '5df41881-3aed-3515-88a7-2f4a814cf09e');
      if (name === 'v5') assert.equal(value, '2ed6657d-e927-568b-95e1-2665a8aea6a2');
    }
  });
}

test('SockJS still creates connections with CommonJS uuid.v4()', () => {
  const { SockJSConnection } = require('sockjs/lib/transport');
  const session = { prefix: '/sockjs', send: message => message };
  const first = new SockJSConnection(session);
  const second = new SockJSConnection(session);
  assert.equal(uuid.validate(first.id), true);
  assert.equal(uuid.version(first.id), 4);
  assert.notEqual(first.id, second.id);
  assert.equal(first.prefix, '/sockjs');
  assert.equal(first.write('message'), 'message');
});

const scriptEnd = /<\/script(?=[\t\n\f\r />])/i;

// GHSA-gfhx-hw2g-v5hg: an initial partial tag must not hide a second closing tag.
for (const closingTag of ['</script>', '</ScRiPt >', '</script\t>', '</script/>']) {
  test(`serialize-javascript escapes nested ${JSON.stringify(closingTag)} in function bodies`, () => {
    const source = `function f(x) { return x</script=+/ + ${JSON.stringify(closingTag + '<img src=x onerror=alert(1)>')}; }`;
    const value = new Function(`return (${source});`)();
    const output = serialize({ value });
    assert.equal(scriptEnd.test(output), false, output);
    const restored = runInNewContext(`(${output})`);
    assert.equal(typeof restored.value, 'function');
  });
}

test('serialize-javascript preserves serialized function results', () => {
  const value = function () { return '</script><b>hello</b>'; };
  const output = serialize({ value });
  assert.equal(scriptEnd.test(output), false);
  assert.equal(runInNewContext(`(${output})`).value(), value());
});

test('serialize-javascript preserves data and JSON escaping', () => {
  const value = '</script><img src=x onerror=alert(1)>';
  for (const options of [undefined, { isJSON: true }]) {
    const output = serialize({ value }, options);
    assert.equal(scriptEnd.test(output), false);
    assert.equal(JSON.parse(output).value, value);
  }
});
