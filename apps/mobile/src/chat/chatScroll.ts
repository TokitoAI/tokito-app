/** Scroll intent belongs to the reader, never to layout/replay callbacks. */
export class ChatScrollIntent {
  following = true;
  private interacting = false;
  open(): void { this.following = true; this.interacting = false; }
  latest(): void { this.open(); }
  drag(): void { this.interacting = true; }
  end(): void { this.interacting = false; }
  earlier(): void { this.following = false; this.interacting = false; }
  scroll(height: number, viewport: number, offset: number): void {
    if (this.interacting) this.following = height - viewport - offset < 100;
  }
}
