import { arrival, browser } from './common.js';
export { handleSummary, setup } from './common.js';
// Four fixed arrival windows; RPS controls arrivals, VUs only provide execution slots.
export const options = {
  scenarios: Object.fromEntries([2, 5, 10, 10].map((rate, index) => [
    `warmup_${index + 1}`, { ...arrival(rate, '10s', 'buyPay', `${index * 10}s`), preAllocatedVUs: 40, maxVUs: 40 },
  ])),
  thresholds: { dropped_iterations: ['count==0'], target_unexpected: ['rate==0'],
    target_payment_rejected: ['rate==0'] },
};
export function buyPay(timing) { browser(true, true, timing); }
