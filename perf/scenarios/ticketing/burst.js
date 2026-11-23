// 정해진 시각에 사용자별로 좌석 조회를 한 번씩 보낸다.
import { createRuntime } from './common.js';

const scenario = createRuntime('burst', 'simultaneous', null);
export const options = scenario.options;
export const setup = scenario.setup;
export const handleSummary = scenario.handleSummary;
export default scenario.run;
