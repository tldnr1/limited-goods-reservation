import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const productId = Number(__ENV.PRODUCT_ID || 1);
const stockStrategy = __ENV.STOCK_STRATEGY || 'redis-lua';
const vus = Number(__ENV.VUS || 100);
const iterations = Number(__ENV.ITERATIONS || vus);
const runId = __ENV.RUN_ID || 'v3-2-local';
const scenarioMode = __ENV.SCENARIO_MODE || 'normal';
const duplicateRequests = Number(__ENV.DUPLICATE_REQUESTS || 1);

export const options = {
  summaryTrendStats: ['min', 'avg', 'med', 'p(50)', 'p(95)', 'p(99)', 'max'],
  tags: {
    version: 'v3.2',
    strategy: stockStrategy,
    scenario: scenarioMode,
    run_id: runId,
    users: String(vus),
  },
  scenarios: {
    reservation_consistency: {
      executor: 'shared-iterations',
      vus,
      iterations,
      maxDuration: __ENV.MAX_DURATION || '2m',
    },
  },
};

const reservationAttempts = new Counter('reservation_attempts');
const reservationCreated = new Counter('reservation_created');
const reservationReused = new Counter('reservation_reused');
const soldOutResponses = new Counter('sold_out_responses');
const alreadyReservedResponses = new Counter('already_reserved_responses');
const retryableFailureResponses = new Counter('retryable_failure_responses');
const activeTokenRequiredResponses = new Counter('active_token_required_responses');
const unexpectedResponses = new Counter('unexpected_responses');
const reservationReqDuration = new Trend('reservation_req_duration');

const expectedCodes = new Set([
  'SOLD_OUT',
  'ALREADY_RESERVED',
  'RESERVATION_FAILED_RETRYABLE',
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
  const idempotencyKey = `${runId}-${userId}`;
  purchase(userId, idempotencyKey);

  if (scenarioMode !== 'duplicate') {
    return;
  }

  for (let i = 1; i < duplicateRequests; i += 1) {
    purchase(userId, idempotencyKey);
  }
}

function purchase(userId, idempotencyKey) {
  reservationAttempts.add(1);
  const response = http.post(
    `${baseUrl}/api/v1/purchases`,
    JSON.stringify({ productId }),
    {
      headers: {
        'Content-Type': 'application/json',
        'X-USER-ID': String(userId),
        'X-IDEMPOTENCY-KEY': idempotencyKey,
        'X-RUN-ID': runId,
      },
    },
  );
  reservationReqDuration.add(response.timings.duration);

  check(response, {
    'reservation response is expected': (res) => (
      res.status === 201 || res.status === 200 || expectedCodes.has(responseCode(res))
    ),
  });

  if (response.status === 201) {
    reservationCreated.add(1);
    return;
  }
  if (response.status === 200) {
    reservationReused.add(1);
    return;
  }

  const code = responseCode(response);
  if (code === 'SOLD_OUT') {
    soldOutResponses.add(1);
    return;
  }
  if (code === 'ALREADY_RESERVED') {
    alreadyReservedResponses.add(1);
    return;
  }
  if (code === 'RESERVATION_FAILED_RETRYABLE') {
    retryableFailureResponses.add(1);
    return;
  }
  if (code === 'ACTIVE_TOKEN_REQUIRED') {
    activeTokenRequiredResponses.add(1);
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
  const duration = data.metrics.http_req_duration?.values || {};
  const reservationDuration = data.metrics.reservation_req_duration?.values || {};

  return {
    stdout: [
      'v3.2 reservation consistency',
      `run_id=${runId}`,
      `stock_strategy=${stockStrategy}`,
      `scenario_mode=${scenarioMode}`,
      `product_id=${productId}`,
      `users=${vus}`,
      `iterations=${iterations}`,
      `duplicate_requests=${duplicateRequests}`,
      `http_req_duration_p50_ms=${duration['p(50)'] || duration.med || 0}`,
      `http_req_duration_p95_ms=${duration['p(95)'] || 0}`,
      `http_req_duration_p99_ms=${duration['p(99)'] || 0}`,
      `reservation_req_duration_p50_ms=${reservationDuration['p(50)'] || reservationDuration.med || 0}`,
      `reservation_req_duration_p95_ms=${reservationDuration['p(95)'] || 0}`,
      `reservation_req_duration_p99_ms=${reservationDuration['p(99)'] || 0}`,
      `reservation_attempts=${data.metrics.reservation_attempts?.values.count || 0}`,
      `reservation_created=${data.metrics.reservation_created?.values.count || 0}`,
      `reservation_reused=${data.metrics.reservation_reused?.values.count || 0}`,
      `sold_out_responses=${data.metrics.sold_out_responses?.values.count || 0}`,
      `already_reserved_responses=${data.metrics.already_reserved_responses?.values.count || 0}`,
      `retryable_failure_responses=${data.metrics.retryable_failure_responses?.values.count || 0}`,
      `active_token_required_responses=${data.metrics.active_token_required_responses?.values.count || 0}`,
      `unexpected_responses=${data.metrics.unexpected_responses?.values.count || 0}`,
      '',
    ].join('\n'),
    [`/results/${runId}.summary.json`]: JSON.stringify(data, null, 2),
  };
}
