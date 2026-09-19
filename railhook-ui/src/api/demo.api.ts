import { http } from './http';
import type { DemoSessionResponse } from '../types/api.types';

/** The live demo: a read-only session in the demo organization, for a visitor with no account. */
export const demoApi = {
  /** The challenge answer, when the deployment asks for one (the same CAPTCHA as registration). */
  createSession: (captchaToken?: string): Promise<DemoSessionResponse> =>
    http.post<DemoSessionResponse>('/api/v1/public/demo/session', captchaToken ? { captchaToken } : {}),
};
