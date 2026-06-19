import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const productId = Number(__ENV.PRODUCT_ID || 1);
const stockStrategy = __ENV.STOCK_STRATEGY || 'rdb-atomic';
const vus = Number(__ENV.VUS || 500);
const iterations = Number(__ENV.ITERATIONS || 500);
const runId = __ENV.RUN_ID || `${stockStrategy}-failure-local`;
const repeat = Number(__ENV.REPEAT || 1);
const initialStock = Number(__ENV.INITIAL_STOCK || 100);
const failureMode = __ENV.PURCHASE_FAILURE_MODE || 'AFTER_STOCK_DECISION_BEFORE_ORDER_SAVE';
const failureLimit = Number(__ENV.PURCHASE_FAILURE_LIMIT || 10);

export const options = {
  summaryTrendStats: ['min', 'avg', 'med', 'p(50)', 'p(95)', 'p(99)', 'max'],
  tags: {
    strategy: stockStrategy,
    run_id: runId,
    users: String(vus),
    repeat: String(repeat),
    initial_stock: String(initialStock),
    failure_mode: failureMode,
    failure_limit: String(failureLimit),
  },
  scenarios: {
    stock_strategy_failure: {
      executor: 'shared-iterations',
      vus,
      iterations,
      maxDuration: __ENV.MAX_DURATION || '2m',
    },
  },
};

const successfulPurchases = new Counter('successful_purchases');
const soldOutResponses = new Counter('sold_out_responses');
const injectedFailureResponses = new Counter('injected_failure_responses');
const unexpectedResponses = new Counter('unexpected_responses');

const expectedFailureCodes = new Set([
  'SOLD_OUT',
  'INJECTED_ORDER_SAVE_FAILURE',
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
        'X-RUN-ID': runId,
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

  if (code === 'INJECTED_ORDER_SAVE_FAILURE') {
    injectedFailureResponses.add(1);
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
  const injectedFailure = data.metrics.injected_failure_responses?.values.count || 0;
  const unexpected = data.metrics.unexpected_responses?.values.count || 0;
  const duration = data.metrics.http_req_duration?.values || {};
  const httpReqs = data.metrics.http_reqs?.values.count || 0;
  const httpReqFailedRate = data.metrics.http_req_failed?.values.rate || 0;

  return {
    stdout: [
      'v2 stock strategy failure injection',
      `run_id=${runId}`,
      `stock_strategy=${stockStrategy}`,
      `product_id=${productId}`,
      `users=${vus}`,
      `iterations=${iterations}`,
      `repeat=${repeat}`,
      `initial_stock=${initialStock}`,
      `failure_mode=${failureMode}`,
      `failure_limit=${failureLimit}`,
      `http_reqs=${httpReqs}`,
      `http_req_failed_rate=${httpReqFailedRate}`,
      `http_req_duration_p50_ms=${duration['p(50)'] || duration.med || 0}`,
      `http_req_duration_p95_ms=${duration['p(95)'] || 0}`,
      `http_req_duration_p99_ms=${duration['p(99)'] || 0}`,
      `successful_purchases=${successful}`,
      `sold_out_responses=${soldOut}`,
      `injected_failure_responses=${injectedFailure}`,
      `unexpected_responses=${unexpected}`,
      '',
    ].join('\n'),
    [`/results/${runId}.failure.summary.json`]: JSON.stringify(data, null, 2),
  };
}
