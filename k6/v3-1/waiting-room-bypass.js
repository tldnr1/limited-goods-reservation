import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const productId = Number(__ENV.PRODUCT_ID || 1);
const vus = Number(__ENV.VUS || 100);
const iterations = Number(__ENV.ITERATIONS || vus);
const runId = __ENV.RUN_ID || 'v3-1-waiting-room-bypass-local';

export const options = {
  summaryTrendStats: ['min', 'avg', 'med', 'p(50)', 'p(95)', 'p(99)', 'max'],
  tags: {
    version: 'v3.1',
    scenario: 'waiting-room-bypass',
    run_id: runId,
    users: String(vus),
  },
  scenarios: {
    waiting_room_bypass: {
      executor: 'shared-iterations',
      vus,
      iterations,
      maxDuration: __ENV.MAX_DURATION || '2m',
    },
  },
};

const activeTokenRequiredResponses = new Counter('active_token_required_responses');
const unexpectedResponses = new Counter('unexpected_responses');

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

  const code = responseCode(response);
  check(response, {
    'purchase is rejected without active token': (res) => res.status === 409 && code === 'ACTIVE_TOKEN_REQUIRED',
  });

  if (response.status === 409 && code === 'ACTIVE_TOKEN_REQUIRED') {
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

  return {
    stdout: [
      'v3.1 waiting room bypass',
      `run_id=${runId}`,
      `product_id=${productId}`,
      `users=${vus}`,
      `iterations=${iterations}`,
      `http_req_duration_p50_ms=${duration['p(50)'] || duration.med || 0}`,
      `http_req_duration_p95_ms=${duration['p(95)'] || 0}`,
      `http_req_duration_p99_ms=${duration['p(99)'] || 0}`,
      `active_token_required_responses=${data.metrics.active_token_required_responses?.values.count || 0}`,
      `unexpected_responses=${data.metrics.unexpected_responses?.values.count || 0}`,
      '',
    ].join('\n'),
    [`/results/${runId}.summary.json`]: JSON.stringify(data, null, 2),
  };
}
