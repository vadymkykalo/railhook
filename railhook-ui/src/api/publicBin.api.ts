import { http } from './http';

export interface PublicBinRequest {
  id: number;
  method: string;
  query: string | null;
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
  requests: PublicBinRequest[];
}

export const publicBinApi = {
  create: (captchaToken?: string): Promise<PublicBin> =>
    http.post<PublicBin>('/api/v1/public/bins', captchaToken ? { captchaToken } : {}),
  get: (slug: string): Promise<PublicBin> => http.get<PublicBin>(`/api/v1/public/bins/${slug}`),
};
