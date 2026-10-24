// Error reporting utilities
export function reportAppError(error: unknown, context: Record<string, unknown> = {}) {
  console.error("Application error:", error, context);
}
