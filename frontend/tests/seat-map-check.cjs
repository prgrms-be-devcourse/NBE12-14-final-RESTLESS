const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");
const assert = require("node:assert/strict");
const ts = require("typescript");

const source = fs.readFileSync(path.join(__dirname, "../src/app/(main)/performances/seat-map.ts"), "utf8");
const js = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 } }).outputText;
const context = { exports: {} };
vm.runInNewContext(js, context);
const { spacedMapSeats, seatPitch } = context.exports;

const grid = (rows, cols, pitch, section = "PERF") => {
  const seats = [];
  for (let r = 0; r < rows; r++) for (let c = 0; c < cols; c++)
    seats.push({ seatId: seats.length + 1, sectionLabel: section, rowLabel: String(r), seatNumber: String(c), posX: c * pitch, posY: r * pitch, grade: "VIP", price: 1, status: "AVAILABLE" });
  return seats;
};

// 성능 시드 배치: 10열 100석, 좌표 1칸 간격. 좌석이 겹치지 않게 넓힌다
const perf = grid(10, 100, 1);
const widened = spacedMapSeats(perf);
assert.equal(seatPitch(widened), 20);
assert.equal(widened.length, perf.length);
assert.equal(perf[1].posX, 1, "원본 좌석 배열은 바꾸지 않는다");
assert.deepEqual(widened.map((s) => s.seatId), perf.map((s) => s.seatId));

// 이미 넉넉한 배치는 좌표를 그대로 둔다
const normal = grid(3, 5, 30, "A");
const kept = spacedMapSeats(normal);
assert.deepEqual(kept.map((s) => [s.posX, s.posY]), normal.map((s) => [s.posX, s.posY]));

// 좌석 하나뿐인 배치도 깨지지 않는다
assert.equal(spacedMapSeats(grid(1, 1, 1)).length, 1);
console.log("좁은 좌석 배치 확대, 넉넉한 배치 유지 검증 통과");
