import { http } from './http';

export interface ClientErrorReport {
  message: string;
  stack?: string;
  componentStack?: string;
  url?: string;
  release?: string;
}

export const clientErrorsApi = {
  /** 202 whether or not it was kept. */
  report: (report: ClientErrorReport): Promise<void> =>
    http.post('/api/v1/client-errors', report),
};
