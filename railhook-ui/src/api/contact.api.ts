import { http } from './http';

export type ContactTopic = 'other' | 'sales' | 'support';

export interface ContactMessage {
  email: string;
  name?: string;
  topic: ContactTopic;
  message: string;
  page?: string;
  captchaToken?: string;
}

export const contactApi = {
  send: (message: ContactMessage): Promise<{ status: string }> =>
    http.post<{ status: string }>('/api/v1/public/contact', message),
};
