import { http } from './http';

export type MembershipRole = 'OWNER' | 'DEVELOPER' | 'VIEWER';
export type MembershipStatus = 'INVITED' | 'ACTIVE' | 'DISABLED';

export interface MemberResponse {
  userId: string;
  email: string;
  role: MembershipRole;
  status: MembershipStatus;
  createdAt: string;
  /** When a pending invite stops being accepted. Absent once it has been accepted. */
  inviteExpiresAt?: string;
  /**
   * The accept-invite link, returned only from `add` and `reissueInvite` and never
   * from `list` — it carries the token. With `EMAIL_ENABLED=false`, the shipped
   * default, this is the only copy that reaches a person.
   */
  inviteUrl?: string;
}

export interface AddMemberRequest {
  email: string;
  role: MembershipRole;
}

export interface ChangeMemberRoleRequest {
  role: MembershipRole;
}

export const membersApi = {
  list: (orgId: string): Promise<MemberResponse[]> => {
    return http.get<MemberResponse[]>(`/api/v1/orgs/${orgId}/members`);
  },

  add: (orgId: string, request: AddMemberRequest): Promise<MemberResponse> => {
    return http.post<MemberResponse>(`/api/v1/orgs/${orgId}/members`, request);
  },

  changeRole: (orgId: string, userId: string, request: ChangeMemberRoleRequest): Promise<MemberResponse> => {
    return http.patch<MemberResponse>(`/api/v1/orgs/${orgId}/members/${userId}`, request);
  },

  /** Replaces a pending invite's token and restarts its 48-hour expiry. */
  reissueInvite: (orgId: string, userId: string): Promise<MemberResponse> => {
    return http.post<MemberResponse>(`/api/v1/orgs/${orgId}/members/${userId}/invite`);
  },

  remove: (orgId: string, userId: string): Promise<void> => {
    return http.delete<void>(`/api/v1/orgs/${orgId}/members/${userId}`);
  },

  /** Keeps the membership and the role; the member is refused access until reinstated. */
  suspend: (orgId: string, userId: string): Promise<MemberResponse> => {
    return http.post<MemberResponse>(`/api/v1/orgs/${orgId}/members/${userId}/suspend`);
  },

  reinstate: (orgId: string, userId: string): Promise<MemberResponse> => {
    return http.post<MemberResponse>(`/api/v1/orgs/${orgId}/members/${userId}/reinstate`);
  },

  acceptInvite: (orgId: string, token: string): Promise<MemberResponse> => {
    return http.post<MemberResponse>(`/api/v1/orgs/${orgId}/members/accept-invite?token=${encodeURIComponent(token)}`);
  },
};
