import { useEffect, useLayoutEffect, useRef } from "react";
import { BackHandler } from "react-native";
import { createBackRegistry } from "./backNavigation";

const subscribe = createBackRegistry(BackHandler);

export function useAndroidBack(handle: () => boolean, priority = 0): void {
  const current = useRef(handle);
  useLayoutEffect(() => { current.current = handle; }, [handle]);
  useEffect(() => subscribe(() => current.current(), priority), [priority]);
}
