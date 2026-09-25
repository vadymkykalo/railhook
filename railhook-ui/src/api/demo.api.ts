import { http } from './http';
import type { DemoSessionResponse } from '../types/api.types';

export const demoApi = {
  createSession: (captchaToken?: string): Promise<DemoSessionResponse> =>
    http.post<DemoSessionResponse>('/api/v1/public/demo/session', captchaToken ? { captchaToken } : {}),
};
