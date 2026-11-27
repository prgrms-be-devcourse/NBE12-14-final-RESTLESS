// 선점 → checkout → 결제 준비 시나리오. 결제 승인은 보내지 않는다. 실행 방법: ../README.md
import { createRuntime } from './common.js';

const scenario = createRuntime('checkout', null, null);
export const options = scenario.options;
export const setup = scenario.setup;
export const handleSummary = scenario.handleSummary;
export default scenario.run;
