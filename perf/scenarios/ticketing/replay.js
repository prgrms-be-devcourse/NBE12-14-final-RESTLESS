// replay 시나리오. 실행 방법: ../README.md
import { createRuntime } from './common.js';

const scenario = createRuntime("replay", null, null);
export const options = scenario.options;
export const setup = scenario.setup;
export const handleSummary = scenario.handleSummary;
export default scenario.run;
