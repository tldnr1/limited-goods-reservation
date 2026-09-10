import { config, arrival, thresholds, browser } from './common.js';
export { handleSummary, setup } from './common.js';
export const options = {
  scenarios: { buyers: arrival(config.rps, `${config.durationSeconds}s`, 'buy') },
  thresholds: thresholds({ target_purchase_accepted_ms: ['p(99)<=1000'] }),
};
export function buy() { browser(true); }
