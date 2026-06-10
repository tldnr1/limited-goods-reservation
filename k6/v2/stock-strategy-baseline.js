import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

export const options = {
  summaryTrendStats: ['min', 'avg', 'med', 'p(50)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    stock_strategy_baseline: {
      executor: 'shared-iterations',
      vus: Number(__ENV.VUS || 1000),
      iterations: Number(__ENV.ITERATIONS || 1000),
      maxDuration: __ENV.MAX_DURATION || '2m',
    },
  },
};

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const productId = Number(__ENV.PRODUCT_ID || 1);
const stockStrategy = __ENV.STOCK_STRATEGY || 'naive-rdb';

const successfulPurchases = new Counter('successful_purchases');
const soldOutResponses = new Counter('sold_out_responses');
const optimisticConflictResponses = new Counter('optimistic_conflict_responses');
const lockBusyResponses = new Counter('lock_busy_responses');
const lockTimeoutResponses = new Counter('lock_timeout_responses');
const unexpectedResponses = new Counter('unexpected_responses');

const expectedFailureCodes = new Set([
  'SOLD_OUT',
  'OPTIMISTIC_CONFLICT',
  'LOCK_BUSY',
  'LOCK_TIMEOUT',
]);

export function setup() {
  for (let i = 0; i < 30; i += 1) {
    const response = http.get(`${baseUrl}/actuator/health`);
    if (response.status === 200) {
      return;
    }
    sleep(1);
  }

  throw new Error('API health endpoint was not ready.');
}

export default function () {
  const userId = exec.scenario.iterationInTest + 1;
  const response = http.post(
    `${baseUrl}/api/v1/purchases`,
    JSON.stringify({ productId }),
    {
      headers: {
        'Content-Type': 'application/json',
        'X-USER-ID': String(userId),
      },
    },
  );

  check(response, {
    'purchase response is created or expected failure': (res) => (
      res.status === 201 || expectedFailureCodes.has(responseCode(res))
    ),
  });

  if (response.status === 201) {
    successfulPurchases.add(1);
    return;
  }

  const code = responseCode(response);

  if (code === 'SOLD_OUT') {
    soldOutResponses.add(1);
    return;
  }

  if (code === 'OPTIMISTIC_CONFLICT') {
    optimisticConflictResponses.add(1);
    return;
  }

  if (code === 'LOCK_BUSY') {
    lockBusyResponses.add(1);
    return;
  }

  if (code === 'LOCK_TIMEOUT') {
    lockTimeoutResponses.add(1);
    return;
  }

  unexpectedResponses.add(1);
}

function responseCode(response) {
  try {
    return response.json('code') || '';
  } catch (error) {
    return '';
  }
}

export function handleSummary(data) {
  const successful = data.metrics.successful_purchases?.values.count || 0;
  const soldOut = data.metrics.sold_out_responses?.values.count || 0;
  const optimisticConflict = data.metrics.optimistic_conflict_responses?.values.count || 0;
  const lockBusy = data.metrics.lock_busy_responses?.values.count || 0;
  const lockTimeout = data.metrics.lock_timeout_responses?.values.count || 0;
  const unexpected = data.metrics.unexpected_responses?.values.count || 0;
  const duration = data.metrics.http_req_duration?.values || {};
  const httpReqs = data.metrics.http_reqs?.values.count || 0;
  const httpReqFailedRate = data.metrics.http_req_failed?.values.rate || 0;

  return {
    stdout: [
      'v2 stock strategy baseline',
      `stock_strategy=${stockStrategy}`,
      `product_id=${productId}`,
      `http_reqs=${httpReqs}`,
      `http_req_failed_rate=${httpReqFailedRate}`,
      `http_req_duration_p50_ms=${duration['p(50)'] || duration.med || 0}`,
      `http_req_duration_p95_ms=${duration['p(95)'] || 0}`,
      `http_req_duration_p99_ms=${duration['p(99)'] || 0}`,
      `successful_purchases=${successful}`,
      `sold_out_responses=${soldOut}`,
      `optimistic_conflict_responses=${optimisticConflict}`,
      `lock_busy_responses=${lockBusy}`,
      `lock_timeout_responses=${lockTimeout}`,
      `unexpected_responses=${unexpected}`,
      'Run the DB verification query to calculate oversell_count.',
      '',
    ].join('\n'),
  };
}
