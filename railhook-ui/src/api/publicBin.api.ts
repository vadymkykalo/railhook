import { http } from './http';

/**
 * The webhook tester on the public site: a URL anyone can make without an account, which
 * records what is sent to it for a day. Anonymous — the dashboard's session plays no part.
 */

export interface PublicBinRequest {
  id: number;
  method: string;
  query: string | null;
  /** Header name to value, credentials and signatures masked. */
  headers: Record<string, string>;
  body: string | null;
  bodyTruncated: boolean;
  sizeBytes: number;
  contentType: string | null;
  sourceIp: string | null;
  receivedAt: string;
}

export interface PublicBin {
  slug: string;
  url: string;
  expiresAt: string;
  requestCount: number;
  /** Newest first, at most the latest hundred. */
  requests: PublicBinRequest[];
}

export const publicBinApi = {
  create: (): Promise<PublicBin> => http.post<PublicBin>('/api/v1/public/bins', {}),
  get: (slug: string): Promise<PublicBin> => http.get<PublicBin>(`/api/v1/public/bins/${slug}`),
};
