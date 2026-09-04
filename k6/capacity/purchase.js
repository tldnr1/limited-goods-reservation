import { check, fail } from 'k6';
import exec from 'k6/execution';
import http from 'k6/http';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8000';
const RATE = Number(__ENV.RATE || 10);
const DURATION = __ENV.DURATION || '60s';
const STOCK = Number(__ENV.STOCK || 1000000);
const PRE_ALLOCATED_VUS = Number(__ENV.PRE_ALLOCATED_VUS || Math.max(20, RATE));

export const options = {
  scenarios: {
    purchase_capacity: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: DURATION,
      preAllocatedVUs: PRE_ALLOCATED_VUS,
      gracefulStop: '10s',
    },
  },
};

export function setup() {
  const response = http.post(
    `${BASE_URL}/admin/sales`,
    JSON.stringify({
      name: `capacity-${Date.now()}`,
      opens_at: new Date(Date.now() - 60000).toISOString(),
      items: [
        {
          name: 'capacity-item',
          price: 10000,
          total_quantity: STOCK,
          per_user_limit: 1,
        },
      ],
    }),
    {
      headers: { 'Content-Type': 'application/json' },
      tags: { name: 'POST /admin/sales' },
    },
  );

  if (response.status !== 201) {
    fail(`capacity sale setup failed: ${response.status} ${response.body}`);
  }

  const sale = response.json();
  console.log(`capacity sale_event_id=${sale.id} sale_item_id=${sale.items[0].id}`);
  return { saleEventId: sale.id, saleItemId: sale.items[0].id };
}

export default function (data) {
  const requestIdentity = `${data.saleItemId}-${exec.scenario.iterationInTest}`;
  const response = http.post(
    `${BASE_URL}/purchases`,
    JSON.stringify({
      sale_event_id: data.saleEventId,
      items: [{ sale_item_id: data.saleItemId, quantity: 1 }],
    }),
    {
      headers: {
        'Content-Type': 'application/json',
        'X-User-Id': requestIdentity,
        'Idempotency-Key': requestIdentity,
      },
      tags: { name: 'POST /purchases' },
    },
  );

  check(response, {
    'purchase created': (result) => result.status === 201,
  });
}
