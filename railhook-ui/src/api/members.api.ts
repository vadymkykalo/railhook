import { http } from './http';

export type MembershipRole = 'OWNER' | 'DEVELOPER' | 'VIEWER';
export type MembershipStatus = 'INVITED' | 'ACTIVE' | 'DISABLED';

export interface MemberResponse {
  userId: string;
  email: string;
  role: MembershipRole;
  status: MembershipStatus;
  createdAt: string;
  inviteExpiresAt?: string;
  /** Only from add and reissueInvite: it carries the token, and with email off it's the only copy. */
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

  reissueInvite: (orgId: string, userId: string): Promise<MemberResponse> => {
    return http.post<MemberResponse>(`/api/v1/orgs/${orgId}/members/${userId}/invite`);
  },

  remove: (orgId: string, userId: string): Promise<void> => {
    return http.delete<void>(`/api/v1/orgs/${orgId}/members/${userId}`);
  },

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
