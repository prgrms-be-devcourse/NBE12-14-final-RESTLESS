const fs = require('node:fs');
const ts = require('typescript');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const source = fs.readFileSync(require('node:path').join(__dirname, '../src/app/(main)/checkout/view.tsx'), 'utf8');
const match = source.match(/  const refund = async \(\) => \{[\s\S]*?\n  \};/);
assert.ok(match);
const js = ts.transpile(match[0] + '\nrefund()', {target:ts.ScriptTarget.ES2022});
async function run(response, initialKey = null) {
  const result = {calls: [], phases: [], statuses: [], key:{current:initialKey}, reloads:0};
  const ctx = {paymentId:42,busy:false,phase:'PAID', window:{confirm:()=>true}, refundKey:result.key,newKey:()=> 'stable-key', setBusy:()=>{},setRefundFailed:()=>{}, setPhase:v=>result.phases.push(v),setRefundStatus:v=>result.statuses.push(v),reload:async()=>{result.reloads++},toast:()=>{},api:async(path,opts)=>{result.calls.push({path,opts}); if(response instanceof Error) throw response; return response},ApiError:Error};
  await vm.runInNewContext(js,ctx);
  return result;
}
(async()=>{
  const completed=await run({status:'COMPLETED'});
  assert.equal(completed.calls[0].path,'/api/payments/42/refunds');
  assert.equal(completed.calls[0].opts.idempotent,'stable-key');
  assert.equal(completed.calls[0].opts.body.reason,'사용자 환불 요청');
  assert.deepEqual(completed.phases,['REFUNDED']);
  for(const status of ['REQUESTED','UNKNOWN']) { const pending=await run({status},'original-key'); assert.equal(pending.calls[0].opts.idempotent,'original-key'); assert.deepEqual(pending.phases,['REFUND_REVIEW']); assert.deepEqual(pending.statuses,[status]); }
  const err=Object.assign(new Error('network'),{isUnavailable:true});
  const failed=await run(err); assert.deepEqual(failed.phases,['REFUND_REVIEW']); assert.equal(failed.key.current,'stable-key');
  console.log('PASS: refund completion, pending, unknown, network failure and idempotency key');
})().catch(e=>{console.error(e);process.exitCode=1});
