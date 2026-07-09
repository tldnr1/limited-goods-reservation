import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const productId = Number(__ENV.PRODUCT_ID || 1);
const architecture = __ENV.PURCHASE_ARCHITECTURE || 'redis-frontgate';
const scenarioMode = __ENV.SCENARIO_MODE || 'normal';
const runId = __ENV.RUN_ID || 'v3-2-architecture-vu-local';
const vus = Number(__ENV.VUS || 1000);
const iterations = Number(__ENV.ITERATIONS || vus);
const duplicateRequests = Number(__ENV.DUPLICATE_REQUESTS || 1);
const initialStock = Number(__ENV.INITIAL_STOCK || 100);
const hikariMaxPoolSize = __ENV.HIKARI_MAX_POOL_SIZE || '10';
const waitingRoomEnabled = __ENV.WAITING_ROOM_ENABLED || 'false';

http.setResponseCallback(http.expectedStatuses({ min: 200, max: 399 }, 409, 503));

export const options = {
  summaryTrendStats: ['min', 'avg', 'med', 'p(50)', 'p(95)', 'p(99)', 'max'],
  tags: {
    version: 'v3.2',
    architecture,
    strategy: architecture,
    scenario: scenarioMode,
    run_id: runId,
    users: String(vus),
    initial_stock: String(initialStock),
    hikari_max_pool_size: hikariMaxPoolSize,
    waiting_room_enabled: waitingRoomEnabled,
  },
  scenarios: {
    architecture_vu_baseline: {
      executor: 'shared-iterations',
      vus,
      iterations,
      maxDuration: __ENV.MAX_DURATION || '3m',
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
const idempotencyProcessingResponses = new Counter('idempotency_processing_responses');
const unexpectedResponses = new Counter('unexpected_responses');
const reservationReqDuration = new Trend('reservation_req_duration');

const expectedCodes = new Set([
  'SOLD_OUT',
  'ALREADY_RESERVED',
  'ACTIVE_TOKEN_REQUIRED',
  'IDEMPOTENCY_PROCESSING',
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
  if (code === 'IDEMPOTENCY_PROCESSING') {
    idempotencyProcessingResponses.add(1);
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
      'v3.2 architecture VU baseline',
      `run_id=${runId}`,
      `architecture=${architecture}`,
      `scenario_mode=${scenarioMode}`,
      `product_id=${productId}`,
      `users=${vus}`,
      `iterations=${iterations}`,
      `duplicate_requests=${duplicateRequests}`,
      `initial_stock=${initialStock}`,
      `hikari_max_pool_size=${hikariMaxPoolSize}`,
      `waiting_room_enabled=${waitingRoomEnabled}`,
      `http_req_failed_rate=${data.metrics.http_req_failed?.values.rate || 0}`,
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
      `idempotency_processing_responses=${data.metrics.idempotency_processing_responses?.values.count || 0}`,
      `unexpected_responses=${data.metrics.unexpected_responses?.values.count || 0}`,
      '',
    ].join('\n'),
    [`/results/${runId}.summary.json`]: JSON.stringify(data, null, 2),
  };
}
