/**
 * Tokito Companion tokens — mirrored from `tokito_ui::Tokens::dark()`
 * (`VtronTokito/ui` `src/tokens.rs`). Keep hexes in lockstep with desktop.
 */

export const tokens = {
  dark: true,
  bg: "#0C0D10",
  bgChrome: "#111216",
  card: "#16181D",
  cardHover: "#1C1F26",
  border: "#24262D",
  borderSoft: "#1D1F25",
  borderStrong: "#363943",
  text: "#ECEDF0",
  text2: "#9A9DA7",
  text3: "#5F626D",
  textDisabled: "#44474F",
  /** Brand mint */
  accent: "#08C9AA",
  accentInk: "#03241D",
  accentSoft: "rgba(8,201,170,0.14)",
  /** Brand indigo */
  accent2: "#5456F0",
  accent2Soft: "rgba(84,86,240,0.16)",
  danger: "#EF5C68",
  warning: "#E0A43F",
  success: "#3ECF8E",
  /** App-icon lockup tile (brand_tile) */
  brandTile: "#07101E",
  /** Schematic / SVG viewer sheet */
  canvasBg: "#14161C",
  selection: "#E07820",
  radiusXs: 4,
  radiusSm: 7,
  radiusMd: 12,
  radiusLg: 16,
  space1: 4,
  space2: 8,
  space3: 12,
  space4: 16,
  space5: 24,
} as const;

/** @deprecated use `tokens` — kept for gradual call-site migration */
export const colors = {
  bg: tokens.bg,
  surface: tokens.card,
  border: tokens.border,
  text: tokens.text,
  textMuted: tokens.text2,
  accent: tokens.accent,
  danger: tokens.danger,
} as const;

/** Tablet master/detail breakpoint (dp). */
export const TABLET_MIN_WIDTH = 768;
