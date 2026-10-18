"use client";

import { useReveal } from "@/lib/hooks";

// Mount once per page to animate elements with the .reveal class as they scroll into view.
export function RevealOnScroll() {
  useReveal();
  return null;
}
