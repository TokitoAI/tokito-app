import { Lexer, type Token, type TokensList } from "marked";

/** Parse Markdown only. No HTML renderer, WebView, remote images or execution. */
export function chatMarkdown(source: string): TokensList | Token[] {
  if (source.length > 512 * 1024) return [{ type: "paragraph", raw: source, text: source }];
  try { return Lexer.lex(source, { gfm: true, breaks: true }); }
  catch { return [{ type: "paragraph", raw: source, text: source }]; }
}
export function safeChatLink(href: string): boolean {
  try { const url = new URL(href); return (url.protocol === "https:" || url.protocol === "http:") && Boolean(url.hostname) && !url.username && !url.password; }
  catch { return false; }
}
