import { config, arrival, thresholds, existingPayment } from './common.js';
export { handleSummary, setup } from './common.js';
export const options = {
  scenarios: { payments: arrival(config.rps, `${config.durationSeconds}s`, 'pay') },
  thresholds: thresholds({ target_payment_rejected: ['rate==0'], target_payment_ms: ['p(95)<=1000'] }),
};
export const pay = existingPayment;
