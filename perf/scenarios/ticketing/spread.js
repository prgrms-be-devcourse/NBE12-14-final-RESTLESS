// 좌석 조회: 3초에 걸쳐 분산 진입한다.
import { createRuntime } from './common.js';

const scenario = createRuntime("poll", "spread", null);
export const options = scenario.options;
export const setup = scenario.setup;
export const handleSummary = scenario.handleSummary;
export default scenario.run;
