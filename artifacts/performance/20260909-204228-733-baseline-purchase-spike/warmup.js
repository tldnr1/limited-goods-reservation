// Same purchase/payment path, separate execution and sale from the measured run.
export { setup, purchase, teardown } from './lib/purchase-flow.js';
export const options = {
  scenarios: {
    warmup: { executor: 'constant-arrival-rate', rate: 10, timeUnit: '1s',
      duration: '30s', preAllocatedVUs: 20, maxVUs: 20, exec: 'purchase' }
  },
  thresholds: { unexpected_errors: ['rate==0'], dropped_iterations: ['count==0'] }
};
