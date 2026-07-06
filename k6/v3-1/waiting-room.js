import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const productId = Number(__ENV.PRODUCT_ID || 1);
const vus = Number(__ENV.VUS || 100);
const iterations = Number(__ENV.ITERATIONS || vus);
const runId = __ENV.RUN_ID || 'v3-1-waiting-room-local';
const maxPolls = Number(__ENV.MAX_POLLS || 30);

export const options = {
  summaryTrendStats: ['min', 'avg', 'med', 'p(50)', 'p(95)', 'p(99)', 'max'],
  tags: {
    version: 'v3.1',
    scenario: 'waiting-room',
    run_id: runId,
    users: String(vus),
  },
  scenarios: {
    waiting_room: {
      executor: 'shared-iterations',
      vus,
      iterations,
      maxDuration: __ENV.MAX_DURATION || '3m',
    },
  },
};

const waitingEntries = new Counter('waiting_entries');
const activeStatuses = new Counter('active_statuses');
const successfulPurchases = new Counter('successful_purchases');
const soldOutResponses = new Counter('sold_out_responses');
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
  let status = enterWaitingRoom(userId);

  for (let poll = 0; status !== 'ACTIVE' && poll < maxPolls; poll += 1) {
    sleep(1);
    status = readWaitingStatus(userId);
  }

  if (status !== 'ACTIVE') {
    unexpectedResponses.add(1);
    return;
  }

  activeStatuses.add(1);
  purchase(userId);
}

function enterWaitingRoom(userId) {
  const response = http.post(
    `${baseUrl}/api/v3/waiting-room/enter`,
    JSON.stringify({ productId }),
    {
      headers: {
        'Content-Type': 'application/json',
        'X-USER-ID': String(userId),
      },
    },
  );

  check(response, {
    'enter response is OK': (res) => res.status === 200,
  });

  const status = responseStatus(response);
  if (status === 'WAITING') {
    waitingEntries.add(1);
  }
  return status;
}

function readWaitingStatus(userId) {
  const response = http.get(
    `${baseUrl}/api/v3/waiting-room/status?productId=${productId}`,
    {
      headers: {
        'X-USER-ID': String(userId),
      },
    },
  );

  check(response, {
    'status response is OK': (res) => res.status === 200,
  });

  return responseStatus(response);
}

function purchase(userId) {
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

function responseStatus(response) {
  try {
    return response.json('status') || '';
  } catch (error) {
    return '';
  }
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
      'v3.1 waiting room',
      `run_id=${runId}`,
      `product_id=${productId}`,
      `users=${vus}`,
      `iterations=${iterations}`,
      `http_req_duration_p50_ms=${duration['p(50)'] || duration.med || 0}`,
      `http_req_duration_p95_ms=${duration['p(95)'] || 0}`,
      `http_req_duration_p99_ms=${duration['p(99)'] || 0}`,
      `waiting_entries=${data.metrics.waiting_entries?.values.count || 0}`,
      `active_statuses=${data.metrics.active_statuses?.values.count || 0}`,
      `successful_purchases=${data.metrics.successful_purchases?.values.count || 0}`,
      `sold_out_responses=${data.metrics.sold_out_responses?.values.count || 0}`,
      `active_token_required_responses=${data.metrics.active_token_required_responses?.values.count || 0}`,
      `unexpected_responses=${data.metrics.unexpected_responses?.values.count || 0}`,
      '',
    ].join('\n'),
    [`/results/${runId}.summary.json`]: JSON.stringify(data, null, 2),
  };
}
