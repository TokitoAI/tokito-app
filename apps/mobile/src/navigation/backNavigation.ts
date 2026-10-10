export type Route =
  | { name: "projects" }
  | { name: "models" }
  | { name: "project"; id: string }
  | { name: "live_schematic"; projectId: string; designId: string }
  | { name: "svg"; projectId: string; artifact: string };

export type SignedOutView = "home" | "sign_in" | "models";
export type ProjectSection = "design" | "chat" | "review";

export function parentRoute(route: Route): Route | null {
  switch (route.name) {
    case "live_schematic":
    case "svg": return { name: "project", id: route.projectId };
    case "project":
    case "models": return { name: "projects" };
    case "projects": return null;
  }
}

export function parentSignedOutView(view: SignedOutView): SignedOutView | null {
  return view === "home" ? null : "home";
}

export function parentProjectSection(section: ProjectSection): ProjectSection | null {
  return section === "design" ? null : "design";
}

type BackSource = {
  addEventListener(event: "hardwareBackPress", callback: () => boolean): { remove(): void };
};

/** Explicit priority avoids dependence on parent/child React effect order. */
export function createBackRegistry(source: BackSource) {
  const handlers = new Map<symbol, { priority: number; handle: () => boolean }>();
  let subscription: { remove(): void } | null = null;
  let handling = false;
  const dispatch = (): boolean => {
    if (handling) return true;
    handling = true;
    try {
      const ordered = [...handlers.entries()].sort((a, b) => b[1].priority - a[1].priority);
      for (const [id, entry] of ordered) {
        if (handlers.has(id) && entry.handle()) return true;
      }
      return false;
    } finally { handling = false; }
  };
  return (handle: () => boolean, priority = 0): (() => void) => {
    const id = Symbol();
    handlers.set(id, { priority, handle });
    subscription ??= source.addEventListener("hardwareBackPress", dispatch);
    return () => {
      handlers.delete(id);
      if (handlers.size === 0) { subscription?.remove(); subscription = null; }
    };
  };
}
