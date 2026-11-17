// 좌석 조회: 동시 진입한다.
import { createRuntime } from './common.js';

const scenario = createRuntime("poll", "simultaneous", null);
export const options = scenario.options;
export const setup = scenario.setup;
export const handleSummary = scenario.handleSummary;
export default scenario.run;
