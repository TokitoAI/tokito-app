/** Scroll intent belongs to the reader, never to layout/replay callbacks. */
export class ChatScrollIntent {
  following = true;
  private interacting = false;
  private contentHeight = 0;
  private viewportHeight = 0;
  open(): void { this.following = true; this.interacting = false; this.contentHeight = 0; }
  latest(): void { this.following = true; this.interacting = false; }
  content(height: number): void { this.contentHeight = height; }
  layout(height: number): void { this.viewportHeight = height; }
  target(): number | null {
    return this.following && !this.interacting && this.contentHeight > 0 && this.viewportHeight > 0
      ? Math.max(0, this.contentHeight - this.viewportHeight) : null;
  }
  drag(): void { this.interacting = true; }
  end(): void { this.interacting = false; }
  earlier(): void { this.following = false; this.interacting = false; }
  scroll(height: number, viewport: number, offset: number): void {
    this.content(height); this.layout(viewport);
    if (this.interacting) this.following = height - viewport - offset < 100;
  }
  needsFollow(offset: number): boolean {
    const target = this.target();
    return target !== null && Math.abs(target - offset) > 1;
  }
}

/** Defer native scroll commands until layout commits, and read current measurements then. */
export class ChatFollowFrame {
  private frame: number | null = null;
  private animated = false;
  constructor(
    private readonly intent: ChatScrollIntent,
    private readonly schedule: (callback: () => void) => number,
    private readonly cancelFrame: (frame: number) => void,
    private readonly apply: (offset: number, animated: boolean) => void,
  ) {}
  request(animated = false): void {
    this.animated ||= animated;
    if (this.frame !== null) return;
    this.frame = this.schedule(() => {
      this.frame = null;
      const animated = this.animated;
      this.animated = false;
      const offset = this.intent.target();
      if (offset !== null) this.apply(offset, animated);
    });
  }
  cancel(): void {
    if (this.frame !== null) this.cancelFrame(this.frame);
    this.frame = null; this.animated = false;
  }
}
