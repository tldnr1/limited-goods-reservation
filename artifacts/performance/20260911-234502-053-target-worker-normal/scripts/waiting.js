import { config, arrival, thresholds, browser } from './common.js';
export { handleSummary, setup } from './common.js';
export const options = { scenarios: { browsers: arrival(config.rps, `${config.durationSeconds}s`, 'joinPoll') }, thresholds: thresholds() };
export function joinPoll() { browser(); }
