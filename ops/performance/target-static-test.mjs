// Offline contract tests: synthetic k6 modules, fake clock, no network or real sleep.
// node --experimental-vm-modules ops/performance/target-static-test.mjs
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
let passed = 0;
async function harness(stage, overrides = {}, responses = [], shared = { arrays: new Map(), orderReads: 0 }) {
  let now = 1800000000000;
  const calls = [];
  const sleeps = [];
  const metrics = {};
  const config = { runId: 'offline', scenario: stage, variant: 'normal', rps: 40, paymentRps: 40,
    durationSeconds: 60, stock: 1000, users: 50000, vus: 100, maxVus: 2000, seed: 20260911, ...overrides };
  const fixture = { sale: { id: 'sale', items: [{ id: 'item' }] } };
  const orders = ['worker', 'isolation'].includes(stage)
    ? [{ id: 'held-order', user: 'fixture-0' }, { id: 'held-order-2', user: 'fixture-1' }] : [];
  let loadingShared = false;
  class SharedArray {
    constructor(name, loader) {
      if (!shared.arrays.has(name)) {
        loadingShared = true;
        const values = loader();
        loadingShared = false;
        assert.ok(Array.isArray(values));
        shared.arrays.set(name, Array.from(values, value => JSON.stringify(value)));
      }
      const values = shared.arrays.get(name);
      return new Proxy([], {
        get(_, key) {
          if (key === 'length') return values.length;
          if (/^\d+$/.test(String(key))) return JSON.parse(values[Number(key)]);
          throw new Error(`Unexpected SharedArray operation: ${String(key)}`);
        },
        set() { throw new Error('SharedArray is read-only'); },
      });
    }
  }
  const execution = { scenario: { name: stage === 'business' ? 'opening' : stage, iterationInTest: 0 } };
  class Metric {
    constructor(name) { this.name = name; metrics[name] = []; }
    add(value, tags = {}) { metrics[this.name].push({ value, tags }); }
  }
  class FakeDate extends Date {
    constructor(...args) { super(...(args.length ? args : [now])); }
    static now() { return now; }
  }
  const context = vm.createContext({
    __ENV: { TARGET_CONFIG: 'config', TARGET_FIXTURE: 'fixture', TARGET_ORDERS: 'orders' }, Date: FakeDate,
    open: name => {
      if (name === 'orders') {
        assert.ok(loadingShared, 'Large orders file must be opened inside SharedArray');
        shared.orderReads++;
      }
      return JSON.stringify(name === 'config' ? config : name === 'orders' ? orders : fixture);
    }, console: { log() {} },
  });
  const http = {
    expectedStatuses: (...args) => args,
    request(method, url, body, params) {
      calls.push({ method, url, body: body && JSON.parse(body), params });
      assert.ok(responses.length, `Unexpected mocked request: ${url}`);
      const next = responses.shift();
      const value = typeof next.value === 'function' ? next.value(now) : next.value;
      return { status: next.status, json: () => value, timings: { duration: 7 } };
    },
  };
  const externals = {
    'k6/http': { default: http }, 'k6/execution': { default: execution },
    'k6/metrics': { Counter: Metric, Rate: Metric, Trend: Metric },
    'k6/data': { SharedArray },
    k6: { sleep: seconds => { assert.ok(Number.isFinite(seconds) && seconds >= 0); sleeps.push(seconds); now += seconds * 1000; } },
  };
  const cache = new Map();
  async function load(name) {
    if (cache.has(name)) return cache.get(name);
    let module;
    if (externals[name]) {
      const values = externals[name];
      module = new vm.SyntheticModule(Object.keys(values), function () {
        for (const [key, value] of Object.entries(values)) this.setExport(key, value);
      }, { context, identifier: name });
    } else {
      module = new vm.SourceTextModule(fs.readFileSync(name, 'utf8'), { context, identifier: name });
    }
    cache.set(name, module);
    await module.link((specifier, parent) => load(specifier.startsWith('.') ? path.resolve(path.dirname(parent.identifier), specifier) : specifier));
    return module;
  }
  const module = await load(path.join(root, 'k6/target', `${stage}.js`));
  await module.evaluate();
  return { module: module.namespace, common: cache.get(path.join(root, 'k6/target/common.js')).namespace,
    config, calls, sleeps, metrics, execution, responses };
}
const joined = () => ({ status: 202, value: { id: 'admission', state: 'WAITING', retryAfter: 1 } });
const ready = () => ({ status: 200, value: now => ({ id: 'admission', state: 'READY', ticket: 'real-ticket', expiresAt: now + 10000 }) });
const held = () => ({ status: 201, value: now => ({ id: 'new-order', holdExpiresAt: new Date(now + 300000).toISOString() }) });
const paid = () => ({ status: 202, value: { id: 'attempt' } });

{
  const shared = { arrays: new Map(), orderReads: 0 };
  const first = await harness('worker', {}, [paid()], shared);
  const second = await harness('worker', {}, [paid()], shared);
  second.execution.scenario.iterationInTest = 1;
  first.module.pay(); second.module.pay();
  assert.equal(shared.orderReads, 1, 'Two VU contexts must load the large file once');
  assert.ok(first.calls[0].url.includes('/held-order/'));
  assert.ok(second.calls[0].url.includes('/held-order-2/'));
  assert.equal(second.calls[0].params.headers['X-User-Id'], 'fixture-1');
  second.execution.scenario.iterationInTest = 2;
  assert.throws(() => second.module.pay(), /Payment fixture exhausted/);
  assert.deepEqual(Object.keys(first.module.setup()), ['startedAt'], 'setup must not serialize shared orders');
  passed++;
}

for (const stage of ['worker', 'waiting', 'reservation', 'isolation', 'business']) {
  const h = await harness(stage);
  assert.ok(h.module.options.scenarios);
  assert.equal(h.calls.length, 0, 'Import must not send HTTP');
  assert.equal(h.module.options.thresholds.dropped_iterations[0], 'count==0');
  passed++;
}
{
  const h = await harness('worker', {}, [paid()]);
  h.module.pay();
  assert.equal(h.calls[0].url, 'http://checkout-nginx/api/orders/held-order/payments');
  assert.equal(h.calls.length, 1);
  assert.equal(h.metrics.target_payment_accepted.length, 1);
  passed++;
}
{
  const h = await harness('waiting', {}, [joined(), ready()]);
  h.module.joinPoll();
  assert.equal(h.calls.length, 2);
  assert.ok(h.calls.every(call => call.url.startsWith('http://nginx/api/admissions')));
  assert.ok(h.sleeps[0] >= 1 && h.sleeps[0] <= 1.25);
  passed++;
}
{
  const h = await harness('reservation', {}, [joined(), ready(), { status: 429, value: { code: 'PURCHASE_BUSY' } }, held()]);
  h.module.buy();
  const purchases = h.calls.filter(call => call.url.endsWith('/api/purchases'));
  assert.equal(purchases.length, 2);
  assert.deepEqual(purchases[0].body, purchases[1].body);
  assert.equal(purchases[0].params.headers['Idempotency-Key'], purchases[1].params.headers['Idempotency-Key']);
  assert.equal(purchases[0].params.headers['X-Admission-Ticket'], 'real-ticket');
  assert.equal(h.metrics.target_unexpected.filter(m => m.value).length, 0);
  passed++;
}
{
  const h = await harness('business', { variant: 'retry' }, [joined(), ready(), held(), { status: 201, value: { id: 'new-order' } }, paid()]);
  h.module.buyPay(h.module.setup());
  assert.equal(h.metrics.target_held.length, 1);
  assert.equal(h.metrics.target_payment_accepted.length, 1);
  assert.equal(h.calls[3].params.headers['Idempotency-Key'], 'purchase');
  passed++;
}
{
  const h = await harness('business', { variant: 'late-payment' }, [joined(), ready(), held(), paid()]);
  h.module.buyPay(h.module.setup());
  assert.ok(h.sleeps.some(seconds => Math.abs(seconds - 299) < 0.002), 'Late payment must use real hold expiry, with fake-clock wait in this test');
  passed++;
}
{
  const h = await harness('business', { variant: 'burst' }, [joined(), ready(), held(), paid()]);
  h.module.buyPay(h.module.setup());
  assert.ok(Math.abs(h.sleeps.reduce((a, b) => a + b) - 60) < 0.001);
  passed++;
}
{
  const h = await harness('business', { variant: 'abandon' }, [joined(), ready(), held()]);
  let index = 0;
  while (h.common.fraction(index, 99) >= 0.2) index++;
  h.execution.scenario.iterationInTest = index;
  h.module.buyPay(h.module.setup());
  assert.equal(h.calls.length, 3);
  assert.equal(h.module.options.scenarios.returning.startTime, '300s');
  assert.equal(h.module.options.scenarios.returning.rate, 1000);
  passed++;
}
{
  const h = await harness('business', { variant: 'pg-failure' }, [joined(), ready(), held(), paid()]);
  let index = 0;
  while (h.common.fraction(index, 99) >= 0.1) index++;
  h.execution.scenario.iterationInTest = index;
  h.module.buyPay(h.module.setup());
  assert.equal(h.calls.at(-1).body.scenario, 'UNKNOWN');
  passed++;
}
{
  const h = await harness('business');
  const phases = Object.values(h.module.options.scenarios);
  assert.equal(phases.reduce((sum, phase) => sum + phase.rate, 0), 50000);
  assert.deepEqual(phases.map(phase => phase.startTime), ['0s', '5s', '15s']);
  const summary = h.module.handleSummary({ metrics: {} });
  assert.ok(summary['/results/k6-summary.json']);
  passed++;
}
console.log(`${passed} offline harness checks passed (no k6, HTTP, or real sleeps).`);
