import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://localhost:8080';
const targetPath = __ENV.TARGET_PATH || '/actuator/health';
const vus = Number(__ENV.VUS || 1000);
const iterations = Number(__ENV.ITERATIONS || vus);
const runId = __ENV.RUN_ID || 'v3-2-generator-sanity';

export const options = {
  summaryTrendStats: ['min', 'avg', 'med', 'p(50)', 'p(95)', 'p(99)', 'max'],
  tags: {
    version: 'v3.2',
    scenario: 'generator-sanity',
    target_path: targetPath,
    run_id: runId,
    users: String(vus),
  },
  scenarios: {
    generator_sanity: {
      executor: 'shared-iterations',
      vus,
      iterations,
      maxDuration: __ENV.MAX_DURATION || '2m',
    },
  },
};

const sanityRequests = new Counter('sanity_requests');
const sanityUnexpected = new Counter('sanity_unexpected_responses');
const sanityReqDuration = new Trend('sanity_req_duration');

export function setup() {
  for (let i = 0; i < 30; i += 1) {
    const response = http.get(`${baseUrl}${targetPath}`);
    if (response.status === 200) {
      return;
    }
    sleep(1);
  }

  throw new Error('Sanity target endpoint was not ready.');
}

export default function () {
  sanityRequests.add(1);
  const response = http.get(`${baseUrl}${targetPath}`);
  sanityReqDuration.add(response.timings.duration);

  const ok = check(response, {
    'sanity response is 200': (res) => res.status === 200,
  });
  if (!ok) {
    sanityUnexpected.add(1);
  }
}

export function handleSummary(data) {
  const duration = data.metrics.http_req_duration?.values || {};
  const sanityDuration = data.metrics.sanity_req_duration?.values || {};
  const httpFailedRate = data.metrics.http_req_failed?.values?.rate || 0;

  return {
    stdout: [
      'v3.2 k6 generator sanity',
      `run_id=${runId}`,
      `target_path=${targetPath}`,
      `users=${vus}`,
      `iterations=${iterations}`,
      `http_reqs=${data.metrics.http_reqs?.values.count || 0}`,
      `http_req_failed_rate=${httpFailedRate}`,
      `http_req_duration_p50_ms=${duration['p(50)'] || duration.med || 0}`,
      `http_req_duration_p95_ms=${duration['p(95)'] || 0}`,
      `http_req_duration_p99_ms=${duration['p(99)'] || 0}`,
      `sanity_req_duration_p50_ms=${sanityDuration['p(50)'] || sanityDuration.med || 0}`,
      `sanity_req_duration_p95_ms=${sanityDuration['p(95)'] || 0}`,
      `sanity_req_duration_p99_ms=${sanityDuration['p(99)'] || 0}`,
      `sanity_requests=${data.metrics.sanity_requests?.values.count || 0}`,
      `sanity_unexpected_responses=${data.metrics.sanity_unexpected_responses?.values.count || 0}`,
      '',
    ].join('\n'),
    [`/results/${runId}.summary.json`]: JSON.stringify(data, null, 2),
  };
}
