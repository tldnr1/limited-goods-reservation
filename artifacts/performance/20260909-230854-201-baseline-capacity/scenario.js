import { createSale } from './lib/purchase-flow.js';
export { purchase, teardown } from './lib/purchase-flow.js';
const rps = Number(__ENV.RPS);
const seconds = Number(__ENV.DURATION_SECONDS || 60);
const stock = Number(__ENV.STOCK || 10000);
if (!Number.isInteger(rps) || rps <= 0 || !Number.isInteger(seconds) || seconds <= 0 ||
    !Number.isInteger(stock) || stock > 1000000 || stock < Math.ceil(rps * seconds * 1.1) + 1) {
  throw new Error('Capacity requires positive integer RPS/duration and stock >= ceil(RPS*seconds*1.1)+1 (max 1000000)');
}
export const options = {
  scenarios: {
    capacity: { executor:'constant-arrival-rate', rate:rps, timeUnit:'1s', duration:`${seconds}s`,
      preAllocatedVUs:64, maxVUs:512, exec:'purchase' }
  },
  thresholds: {
    unexpected_errors:['rate==0'], purchase_rejections:['rate==0'], dropped_iterations:['count==0'],
    'http_req_duration{name:purchase}':['p(99)<1000'],
    'http_req_duration{name:payment_accept}':['p(95)<1000']
  }
};
export function setup() { return createSale(stock); }
