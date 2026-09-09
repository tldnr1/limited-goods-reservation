export { setup, purchase, teardown } from './lib/purchase-flow.js';
export const options = {
  scenarios: {
    opening: { executor: 'constant-arrival-rate', rate: Number(__ENV.OPENING_RPS || 3000),
      timeUnit: '1s', duration: '10s', preAllocatedVUs: 64, maxVUs: 512, exec: 'purchase' },
    tail: { executor: 'constant-arrival-rate', rate: Number(__ENV.TAIL_RPS || 400),
      timeUnit: '1s', startTime: '10s', duration: '50s', preAllocatedVUs: 32, maxVUs: 256, exec: 'purchase' }
  },
  thresholds: {
    unexpected_errors: ['rate<=0.001'], dropped_iterations: ['count==0'],
    'http_req_duration{name:purchase}': ['p(99)<1000'],
    'http_req_duration{name:payment_accept}': ['p(95)<1000']
  }
};
