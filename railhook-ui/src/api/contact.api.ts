import { http } from './http';

/** A message from the public site to the deployment's support address. Anonymous. */

export type ContactTopic = 'other' | 'sales' | 'support';

export interface ContactMessage {
  email: string;
  name?: string;
  topic: ContactTopic;
  message: string;
  /** The page it was sent from, so the reader knows what the visitor was looking at. */
  page?: string;
  captchaToken?: string;
}

export const contactApi = {
  send: (message: ContactMessage): Promise<{ status: string }> =>
    http.post<{ status: string }>('/api/v1/public/contact', message),
};
