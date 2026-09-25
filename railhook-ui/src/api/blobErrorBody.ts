/** A blob request's error body is a Blob too; read JSON back so showApiError sees the message. */
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
