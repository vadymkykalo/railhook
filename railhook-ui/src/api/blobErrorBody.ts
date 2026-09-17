/**
 * A request made with `responseType: 'blob'` gets its error body as a Blob too, so
 * `err.response.data.message` — what showApiError reads — was undefined and a failed export
 * toasted a generic fallback instead of the server's reason. This reads a JSON error body back
 * into an object on the same error; anything else is rethrown untouched.
 */
export async function withJsonErrorBody<T>(request: Promise<T>): Promise<T> {
  try {
    return await request;
  } catch (err) {
    const response = (err as { response?: { data?: unknown } } | null)?.response;
    if (response && response.data instanceof Blob) {
      try {
        response.data = JSON.parse(await response.data.text());
      } catch {
        // Not JSON (a proxy's HTML page, say): leave the body as it was.
      }
    }
    throw err;
  }
}
