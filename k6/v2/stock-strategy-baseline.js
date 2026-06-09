import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

export const options = {
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

  check(response, {
    'purchase response is created or sold out': (res) => res.status === 201 || res.status === 409,
  });

  if (response.status === 201) {
    successfulPurchases.add(1);
    return;
  }

  if (response.status === 409) {
    soldOutResponses.add(1);
    return;
  }

  unexpectedResponses.add(1);
}

export function handleSummary(data) {
  const successful = data.metrics.successful_purchases?.values.count || 0;
  const soldOut = data.metrics.sold_out_responses?.values.count || 0;
  const unexpected = data.metrics.unexpected_responses?.values.count || 0;

  return {
    stdout: [
      'v2 stock strategy baseline',
      `stock_strategy=${stockStrategy}`,
      `product_id=${productId}`,
      `successful_purchases=${successful}`,
      `sold_out_responses=${soldOut}`,
      `unexpected_responses=${unexpected}`,
      'Run the DB verification query to calculate oversell_count.',
      '',
    ].join('\n'),
  };
}
