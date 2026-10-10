import assert from "node:assert/strict";
import test from "node:test";
import { chatMarkdown, safeChatLink } from "./chatMarkdown";

test("answers retain Markdown headings, lists, code, tables and inline emphasis", () => {
  const result = chatMarkdown("## Result\n\n**2.2kΩ**, not *1k*.\n\n- A2\n- K1\n\n```text\nI = 1.3 / 2200\n```\n\n| R | I |\n| - | - |\n| 2.2k | 0.59mA |");
  assert.deepEqual(result.filter(t => t.type !== "space").map(t => t.type), ["heading", "paragraph", "list", "code", "table"]);
});
test("untrusted content remains tokens, never generated HTML or image fetches", () => {
  assert.equal(chatMarkdown('<script>alert(1)</script>')[0].type, "html");
  for (const url of ["javascript:alert(1)", "data:text/html,x", "file:///etc/passwd", "intent://app", "https://user:pass@example.com"]) assert.equal(safeChatLink(url), false);
  assert.equal(safeChatLink("https://tokito.dev/docs"), true);
  assert.equal(safeChatLink("not a URL"), false);
});
