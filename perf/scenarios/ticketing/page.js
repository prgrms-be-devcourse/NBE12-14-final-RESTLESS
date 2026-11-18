// 좌석 페이지 진입: 좌석과 구매 가능 정보를 함께 조회한다.
import { createRuntime } from './common.js';

const scenario = createRuntime('page');
export const options = scenario.options;
export const setup = scenario.setup;
export const handleSummary = scenario.handleSummary;
export default scenario.run;
