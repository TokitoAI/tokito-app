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
    return this.following && this.contentHeight > 0 && this.viewportHeight > 0
      ? Math.max(0, this.contentHeight - this.viewportHeight) : null;
  }
  drag(): void { this.interacting = true; }
  end(): void { this.interacting = false; }
  earlier(): void { this.following = false; this.interacting = false; }
  scroll(height: number, viewport: number, offset: number): void {
    if (this.interacting) this.following = height - viewport - offset < 100;
  }
}
