import { http } from './http';

/**
 * A failure the dashboard could not recover from. Mirrors the backend's
 * ClientErrorReportRequest; every field is capped there and trimmed again before it reaches a
 * log line, so nothing here needs to be short — only true.
 */
export interface ClientErrorReport {
  message: string;
  stack?: string;
  componentStack?: string;
  url?: string;
  release?: string;
}

export const clientErrorsApi = {
  /**
   * Posts one report. Answers 202 whether or not it was kept — the operator may have reporting
   * off, or the per-user cap may already be spent, and a page that has just failed can do
   * nothing useful with the difference.
   */
  report: (report: ClientErrorReport): Promise<void> =>
    http.post('/api/v1/client-errors', report),
};
