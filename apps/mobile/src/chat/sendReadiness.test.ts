import assert from "node:assert/strict";
import test from "node:test";
import { sendBlockReason, type SendReadiness } from "./sendReadiness";
import { readFileSync } from "node:fs";
test("every unavailable send state has visible recovery rather than a silent no-op", () => {
  const ready: SendReadiness = {hasSession:true,ready:true,busy:false,inFlight:false,blocked:false,needsSheet:false,hasText:true};
  assert.equal(sendBlockReason(ready), null);
  for (const change of [{hasSession:false},{ready:false},{busy:true},{inFlight:true},{blocked:true},{needsSheet:true},{hasText:false}]) {
    assert.ok(sendBlockReason({...ready,...change}));
  }
});
test("chat recovery sits between history and composer and both action and button use eligibility", () => {
  const source=readFileSync(new URL("./ProjectChat.tsx",import.meta.url),"utf8");
  const feedback=source.indexOf('{error ? <View accessibilityLiveRegion="polite"');
  assert.ok(feedback>source.indexOf("<FlatList"));
  assert.ok(feedback<source.indexOf("<View style={styles.composer}>"));
  assert.ok(source.includes("disabled={sendProblem !== null}"));
  assert.equal((source.match(/sendBlockReason\(/g) ?? []).length,2);
});
