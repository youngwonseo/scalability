// One constant-arrival-rate step of a workload. Driven by bench/find_limit.py, one run per step.
//
//   k6 run -e WORKLOAD=M1 -e RATE=500 -e DURATION=60s -e USERS=1000000 -e POSTS=10000000 workload.js
//
// IDs of seeded posts are computed, not queried (see README "ID 형식과 적재 데이터").

import http from 'k6/http';
import { Rate } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const USERS = Number(__ENV.USERS || 1000000);
const POSTS = Number(__ENV.POSTS || 10000000);
const WORKLOAD = __ENV.WORKLOAD || 'R1';
const RATE = Number(__ENV.RATE || 100);
const DURATION = __ENV.DURATION || '60s';
// uniform | zipf (hot keys: a few posts/users get most of the traffic)
const DIST = __ENV.DIST || 'uniform';
const HOT_KEYS = Number(__ENV.HOT_KEYS || 1000);
const SUMMARY_PATH = __ENV.SUMMARY_PATH || '';
// S3: read-after-write probe rate (0 = off). Runs alongside the main load as its own scenario.
const RAW_RATE = Number(__ENV.RAW_RATE || 0);

const staleReads = new Rate('stale_reads');

// Operation weights. Mixed workloads split reads R1:R2 and writes W1:W2 evenly.
const MIXES = {
  R1: { R1: 1 },
  R2: { R2: 1 },
  W1: { W1: 1 },
  W2: { W2: 1 },
  M1: { R1: 0.475, R2: 0.475, W1: 0.025, W2: 0.025 },
  M2: { R1: 0.35, R2: 0.35, W1: 0.15, W2: 0.15 },
  M3: { R1: 0.1, R2: 0.1, W1: 0.4, W2: 0.4 },
};

const mix = MIXES[WORKLOAD];
if (!mix) {
  throw new Error(`Unknown WORKLOAD ${WORKLOAD}. Use one of ${Object.keys(MIXES).join(', ')}`);
}
const cumulative = [];
{
  let sum = 0;
  for (const [op, weight] of Object.entries(mix)) {
    sum += weight;
    cumulative.push([sum, op]);
  }
}

export const options = {
  discardResponseBodies: true,
  scenarios: {
    load: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      // VUs needed ~= rate x latency. Allow up to 2 s average latency before k6 starts dropping.
      preAllocatedVUs: Math.min(Math.ceil(RATE * 0.2) + 20, 5000),
      maxVUs: Math.min(RATE * 2 + 100, 20000),
    },
    ...(RAW_RATE > 0 && {
      raw: {
        executor: 'constant-arrival-rate',
        exec: 'readAfterWrite',
        rate: RAW_RATE,
        timeUnit: '1s',
        duration: DURATION,
        preAllocatedVUs: 10,
        maxVUs: 500,
      },
    }),
  },
  summaryTrendStats: ['avg', 'p(50)', 'p(95)', 'p(99)', 'max'],
  // Thresholds make k6 report these sub-metrics; pass/fail is decided by the driver.
  // The SLO is judged on scenario:load only, so the read-after-write probe does not skew it.
  thresholds: {
    'http_req_duration{scenario:load}': ['p(99)<200', 'p(95)<100'],
    'http_req_failed{scenario:load}': ['rate<0.01'],
    'http_reqs{scenario:load}': ['count>=0'],
    'http_req_duration{type:read}': ['p(99)<200'],
    'http_req_duration{type:write}': ['p(99)<200'],
    'http_req_failed{type:read}': ['rate<0.01'],
    'http_req_failed{type:write}': ['rate<0.01'],
    'http_reqs{type:read}': ['count>=0'],
    'http_reqs{type:write}': ['count>=0'],
  },
};

const params = (type, name) => ({ tags: { type, name }, timeout: '5s' });
const jsonParams = (type, name) => ({
  tags: { type, name },
  timeout: '5s',
  headers: { 'Content-Type': 'application/json' },
});

// 1..max, either uniform or roughly Zipfian (s ~= 1) over the first HOT_KEYS values
function pick(max) {
  if (DIST === 'zipf') {
    const hot = Math.min(HOT_KEYS, max);
    return Math.min(hot, Math.floor(Math.exp(Math.random() * Math.log(hot + 1))));
  }
  return 1 + Math.floor(Math.random() * max);
}

// Must match SnowflakeIdGenerator.seededId / DataLoader.ownerOf
function postId(n) {
  const owner = ((n - 1) % USERS) + 1;
  return n * 4194304 + (owner % 1024) * 4096;
}

const ops = {
  R1: () => http.get(`${BASE_URL}/posts/${postId(pick(POSTS))}`, params('read', 'R1')),
  R2: () => http.get(`${BASE_URL}/users/${pick(USERS)}/posts?limit=20`, params('read', 'R2')),
  W1: () =>
    http.post(
      `${BASE_URL}/posts`,
      JSON.stringify({ userId: pick(USERS), content: `load test post ${Date.now()}` }),
      jsonParams('write', 'W1'),
    ),
  W2: () => http.post(`${BASE_URL}/posts/${postId(pick(POSTS))}/like`, null, params('write', 'W2')),
};

export default function () {
  const r = Math.random() * cumulative[cumulative.length - 1][0];
  for (const [bound, op] of cumulative) {
    if (r < bound) {
      ops[op]();
      return;
    }
  }
}

// Create a post and read it back immediately. A 404 means the read hit a replica that has not
// applied the write yet.
export function readAfterWrite() {
  const created = http.post(
    `${BASE_URL}/posts`,
    JSON.stringify({ userId: pick(USERS), content: 'read-after-write probe' }),
    { ...jsonParams('probe', 'RAW-W'), responseType: 'text' },
  );
  if (created.status !== 201) {
    return;
  }
  const read = http.get(`${BASE_URL}/posts/${created.json('id')}`, {
    ...params('probe', 'RAW-R'),
    responseCallback: http.expectedStatuses(200, 404),
  });
  staleReads.add(read.status === 404);
}

export function handleSummary(data) {
  const m = data.metrics;
  const v = (name, stat) => (m[name] ? m[name].values[stat] : undefined);
  const stale = v('stale_reads', 'rate');
  const line =
    `${WORKLOAD} target=${RATE}/s actual=${(v('http_reqs{scenario:load}', 'rate') || 0).toFixed(0)}/s ` +
    `p95=${(v('http_req_duration{scenario:load}', 'p(95)') || 0).toFixed(1)}ms ` +
    `p99=${(v('http_req_duration{scenario:load}', 'p(99)') || 0).toFixed(1)}ms ` +
    `errors=${((v('http_req_failed{scenario:load}', 'rate') || 0) * 100).toFixed(2)}% ` +
    `dropped=${v('dropped_iterations', 'count') || 0}` +
    (stale === undefined ? '' : ` stale_reads=${(stale * 100).toFixed(2)}%`) +
    '\n';
  const out = { stdout: line };
  if (SUMMARY_PATH) {
    out[SUMMARY_PATH] = JSON.stringify(data);
  }
  return out;
}
