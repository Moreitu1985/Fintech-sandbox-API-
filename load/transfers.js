// LedgerForge load test (k6).
//
// Several fintech tenants send transfers between their customer accounts at a constant arrival rate, with
// some balance reads mixed in. Afterwards every tenant's books are checked: postings must sum to zero, every
// balance must match its history, the audit chain must be intact, and the money in customer accounts must
// equal exactly what was funded. A fast ledger that loses a cent fails this test.
//
//   k6 run -e BASE_URL=http://localhost:8080 -e ADMIN_TOKEN=... load/transfers.js
//   Optional: RATE (transfers+reads per second, default 200), DURATION (default 60s),
//             TENANTS (default 10), ACCOUNTS (customer accounts per tenant, default 20)

import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://localhost:8080';
const ADMIN = __ENV.ADMIN_TOKEN;
const RATE = Number(__ENV.RATE || 200);
const DURATION = __ENV.DURATION || '60s';
const TENANTS = Number(__ENV.TENANTS || 10);
const ACCOUNTS = Number(__ENV.ACCOUNTS || 20);
const FUNDING = 1_000_000; // minor units per customer account (R10,000.00)

const transfersPosted = new Counter('transfers_posted');
const transfersDeclined = new Counter('transfers_declined_insufficient_funds');
const rateLimited = new Counter('rate_limited');
const unexpected = new Counter('unexpected_responses');
const integrityFailures = new Counter('integrity_failures');

export const options = {
  scenarios: {
    ledger: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: 50,
      maxVUs: 300,
    },
  },
  thresholds: {
    'http_req_duration{kind:transfer}': ['p(95)<500', 'p(99)<1000'],
    'http_req_duration{kind:read}': ['p(95)<250'],
    unexpected_responses: ['count==0'],
    rate_limited: ['count==0'],
    integrity_failures: ['count==0'],
    dropped_iterations: ['count<' + Math.ceil(RATE * 0.01 * 60)],
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16);
  });
}

function json(method, path, body, headers, tags) {
  return http.request(method, BASE + path, body === null ? null : JSON.stringify(body), {
    headers: Object.assign({ 'Content-Type': 'application/json' }, headers),
    tags: tags,
  });
}

function mustOk(res, what) {
  if (res.status < 200 || res.status > 299) {
    throw new Error(`${what} failed: HTTP ${res.status} ${res.body}`);
  }
  return res.json();
}

export function setup() {
  if (!ADMIN) {
    throw new Error('ADMIN_TOKEN is required');
  }
  const tenants = [];
  for (let t = 0; t < TENANTS; t++) {
    const created = mustOk(json('POST', '/admin/tenants', { name: `Load test fintech ${t}` },
      { 'X-Admin-Token': ADMIN }, { kind: 'setup' }), 'create tenant');
    const auth = { Authorization: 'Bearer ' + created.apiKey.apiKey };
    const treasury = mustOk(json('POST', '/v1/accounts', { name: 'Treasury', currency: 'ZAR', allowNegative: true },
      auth, { kind: 'setup' }), 'open treasury').id;
    const accounts = [];
    for (let a = 0; a < ACCOUNTS; a++) {
      const id = mustOk(json('POST', '/v1/accounts', { name: `Customer ${a}`, currency: 'ZAR' }, auth,
        { kind: 'setup' }), 'open account').id;
      mustOk(json('POST', '/v1/transfers', { fromAccountId: treasury, toAccountId: id, amount: FUNDING, currency: 'ZAR' },
        Object.assign({ 'Idempotency-Key': 'fund-' + id }, auth), { kind: 'setup' }), 'fund account');
      accounts.push(id);
    }
    tenants.push({ key: created.apiKey.apiKey, treasury, accounts });
  }
  return { tenants };
}

export default function (data) {
  const tenant = data.tenants[Math.floor(Math.random() * data.tenants.length)];
  const auth = { Authorization: 'Bearer ' + tenant.key };
  const accounts = tenant.accounts;

  if (Math.random() < 0.2) {
    const res = json('GET', '/v1/accounts/' + accounts[Math.floor(Math.random() * accounts.length)], null, auth,
      { kind: 'read' });
    record(res, [200]);
    return;
  }

  const from = Math.floor(Math.random() * accounts.length);
  let to = Math.floor(Math.random() * (accounts.length - 1));
  if (to >= from) {
    to += 1;
  }
  const res = json('POST', '/v1/transfers',
    { fromAccountId: accounts[from], toAccountId: accounts[to], amount: 1 + Math.floor(Math.random() * 50_000), currency: 'ZAR' },
    Object.assign({ 'Idempotency-Key': uuid() }, auth), { kind: 'transfer' });
  if (res.status === 201) {
    transfersPosted.add(1);
  } else if (res.status === 422 && res.json('code') === 'INSUFFICIENT_FUNDS') {
    transfersDeclined.add(1); // a correct business answer, not an error
  }
  record(res, [201, 422]);
}

function record(res, expected) {
  if (res.status === 429) {
    rateLimited.add(1);
  }
  const ok = check(res, { 'expected status': (r) => expected.includes(r.status) });
  if (!ok) {
    unexpected.add(1);
  }
}

export function teardown(data) {
  for (const tenant of data.tenants) {
    const auth = { Authorization: 'Bearer ' + tenant.key };
    const report = mustOk(json('GET', '/v1/ledger/integrity', null, auth, { kind: 'verify' }), 'integrity');
    let customerTotal = 0;
    let negative = 0;
    for (const id of tenant.accounts) {
      const balance = mustOk(json('GET', '/v1/accounts/' + id, null, auth, { kind: 'verify' }), 'balance').balance;
      customerTotal += balance;
      if (balance < 0) {
        negative += 1;
      }
    }
    const conserved = customerTotal === FUNDING * tenant.accounts.length;
    const ok = check(report, {
      'books balance (postings sum to zero)': (r) => r.postingsSum === 0,
      'balances match their history': (r) => r.balanceMismatches.length === 0,
      'audit chain intact': (r) => r.auditChainValid === true,
      'integrity report healthy': (r) => r.healthy === true,
    }) && check(null, {
      'money conserved across customer accounts': () => conserved,
      'no customer balance below zero': () => negative === 0,
    });
    if (!ok) {
      integrityFailures.add(1);
      console.error(`Integrity failure: ${JSON.stringify(report)} customerTotal=${customerTotal} negative=${negative}`);
    }
  }
}

export function handleSummary(data) {
  const m = data.metrics;
  const v = (name, stat) => (m[name] && m[name].values[stat] !== undefined ? m[name].values[stat] : 0);
  const ms = (name, stat) => v(name, stat).toFixed(1);
  const seconds = data.state.testRunDurationMs / 1000;
  const posted = v('transfers_posted', 'count');
  const declined = v('transfers_declined_insufficient_funds', 'count');
  const md = [
    '## Load test results',
    '',
    `Target ${RATE} requests/s for ${DURATION} across ${TENANTS} tenants x ${ACCOUNTS} accounts (80% transfers, 20% balance reads).`,
    '',
    '| Measure | Value |',
    '|---|---|',
    `| Transfers posted | ${posted} (${(posted / seconds).toFixed(1)}/s over the whole run) |`,
    `| Transfers correctly declined (insufficient funds) | ${declined} |`,
    `| Transfer latency p50 / p95 / p99 | ${ms('http_req_duration{kind:transfer}', 'med')} / ${ms('http_req_duration{kind:transfer}', 'p(95)')} / ${ms('http_req_duration{kind:transfer}', 'p(99)')} ms |`,
    `| Balance read latency p50 / p95 | ${ms('http_req_duration{kind:read}', 'med')} / ${ms('http_req_duration{kind:read}', 'p(95)')} ms |`,
    `| Unexpected responses | ${v('unexpected_responses', 'count')} |`,
    `| Dropped iterations (server couldn't keep up) | ${v('dropped_iterations', 'count')} |`,
    `| Integrity failures after the run | ${v('integrity_failures', 'count')} |`,
    '',
  ].join('\n');
  const out = { stdout: md + '\n' };
  if (__ENV.SUMMARY_DIR) {
    out[__ENV.SUMMARY_DIR + '/summary.md'] = md;
    out[__ENV.SUMMARY_DIR + '/summary.json'] = JSON.stringify(data, null, 2);
  }
  return out;
}
