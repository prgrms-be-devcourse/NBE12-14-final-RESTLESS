const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const assert = require("node:assert/strict");
const ts = require("typescript");

const source = fs.readFileSync(path.join(__dirname, "../src/app/(main)/performances/[id]/seat-state.ts"), "utf8");
const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText;
const context = { exports: {} };
vm.runInNewContext(js, context);
const merge = context.exports.mergeSeatStates;
const original = [{ seatId: 1, sectionLabel: "A", price: 100, status: "AVAILABLE" },
  { seatId: 2, sectionLabel: "B", price: 200, status: "HELD" }];
const merged = merge(original, [{ seatId: 2, status: "SOLD" }, { seatId: 1, status: "HELD" }]);
assert.equal(merged[0].status, "HELD");
assert.equal(merged[1].status, "SOLD");
assert.equal(merged[0].sectionLabel, "A");
assert.equal(merged[1].price, 200);
assert.equal(original[0].status, "AVAILABLE");
for (const states of [[], [{ seatId: 1, status: "HELD" }, { seatId: 1, status: "SOLD" }],
  [{ seatId: 1, status: "UNKNOWN" }, { seatId: 2, status: "SOLD" }],
  [{ seatId: 1, status: "HELD" }, { seatId: 3, status: "SOLD" }]]) {
  assert.equal(merge(original, states), null);
}
console.log("좌석 상태 결합·배치 보존·불일치 응답 검증 통과");
