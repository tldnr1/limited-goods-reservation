import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const productId = Number(__ENV.PRODUCT_ID || 1);
const vus = Number(__ENV.VUS || 100);
const iterations = Number(__ENV.ITERATIONS || vus);
const runId = __ENV.RUN_ID || 'v3-1-direct-local';
const admissionPolicy = __ENV.ADMISSION_POLICY || 'direct';

export const options = {
  summaryTrendStats: ['min', 'avg', 'med', 'p(50)', 'p(95)', 'p(99)', 'max'],
  tags: {
    version: 'v3.1',
    scenario: admissionPolicy,
    run_id: runId,
    users: String(vus),
  },
  scenarios: {
    direct_purchase: {
      executor: 'shared-iterations',
      vus,
      iterations,
      maxDuration: __ENV.MAX_DURATION || '2m',
    },
  },
};

const purchaseAttempts = new Counter('purchase_attempts');
const successfulPurchases = new Counter('successful_purchases');
const soldOutResponses = new Counter('sold_out_responses');
const activeTokenRequiredResponses = new Counter('active_token_required_responses');
const unexpectedResponses = new Counter('unexpected_responses');
const purchaseReqDuration = new Trend('purchase_req_duration');

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
  purchaseAttempts.add(1);

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
  purchaseReqDuration.add(response.timings.duration);

  check(response, {
    'purchase response is created or expected failure': (res) => (
      res.status === 201 || responseCode(res) === 'SOLD_OUT'
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
  const purchaseDuration = data.metrics.purchase_req_duration?.values || {};

  return {
    stdout: [
      'v3.1 direct purchase',
      `run_id=${runId}`,
      `admission_policy=${admissionPolicy}`,
      `product_id=${productId}`,
      `users=${vus}`,
      `iterations=${iterations}`,
      `http_req_duration_p50_ms=${duration['p(50)'] || duration.med || 0}`,
      `http_req_duration_p95_ms=${duration['p(95)'] || 0}`,
      `http_req_duration_p99_ms=${duration['p(99)'] || 0}`,
      `purchase_req_duration_p50_ms=${purchaseDuration['p(50)'] || purchaseDuration.med || 0}`,
      `purchase_req_duration_p95_ms=${purchaseDuration['p(95)'] || 0}`,
      `purchase_req_duration_p99_ms=${purchaseDuration['p(99)'] || 0}`,
      `purchase_attempts=${data.metrics.purchase_attempts?.values.count || 0}`,
      `successful_purchases=${data.metrics.successful_purchases?.values.count || 0}`,
      `sold_out_responses=${data.metrics.sold_out_responses?.values.count || 0}`,
      `active_token_required_responses=${data.metrics.active_token_required_responses?.values.count || 0}`,
      `unexpected_responses=${data.metrics.unexpected_responses?.values.count || 0}`,
      '',
    ].join('\n'),
    [`/results/${runId}.summary.json`]: JSON.stringify(data, null, 2),
  };
}
