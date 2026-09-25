/** Compile-time check that the hand-written mirror still matches the spec; narrower is allowed, wider is not. */
import type { components } from './api.generated';
import type * as Mirror from './api.types';

type Schemas = components['schemas'];

/** Strips null/undefined: springdoc marks nothing required or nullable, so optionality is not drift. */
type DeepDenull<T> =
    T extends (infer U)[] ? DeepDenull<U>[]
        : T extends object ? { [K in keyof T]-?: DeepDenull<NonNullable<T[K]>> }
            : T;

type Conforms<Name extends keyof Schemas, M> =
    [Exclude<keyof M, keyof Schemas[Name]>] extends [never]
        ? DeepDenull<M> extends Required<Pick<Schemas[Name], Extract<keyof M, keyof Schemas[Name]>>>
            ? true
            : { error: 'a property type differs from openapi.yaml'; schema: Name }
        : {
            error: 'the mirror declares a field openapi.yaml does not have';
            schema: Name;
            fields: Exclude<keyof M, keyof Schemas[Name]>;
        };

type Assert<T extends true> = T;

/* eslint-disable @typescript-eslint/no-unused-vars */

export type AuthResponseConforms = Assert<Conforms<'AuthResponse', Mirror.AuthResponse>>;
export type RegisterRequestConforms = Assert<Conforms<'RegisterRequest', Mirror.RegisterRequest>>;
export type ChangeEmailRequestConforms = Assert<Conforms<'ChangeEmailRequest', Mirror.ChangeEmailRequest>>;
export type EmailChangeResponseConforms = Assert<Conforms<'EmailChangeResponse', Mirror.EmailChangeResponse>>;
export type LoginRequestConforms = Assert<Conforms<'LoginRequest', Mirror.LoginRequest>>;
export type UserResponseConforms = Assert<Conforms<'UserResponse', Mirror.UserResponse>>;
export type CurrentUserResponseConforms = Assert<Conforms<'CurrentUserResponse', Mirror.CurrentUserResponse>>;
export type DemoSessionResponseConforms = Assert<Conforms<'DemoSessionResponse', Mirror.DemoSessionResponse>>;
export type OrganizationResponseConforms = Assert<Conforms<'OrganizationResponse', Mirror.OrganizationResponse>>;
export type ProjectRequestConforms = Assert<Conforms<'ProjectRequest', Mirror.ProjectRequest>>;
export type ProjectResponseConforms = Assert<Conforms<'ProjectResponse', Mirror.ProjectResponse>>;
export type EndpointRequestConforms = Assert<Conforms<'EndpointRequest', Mirror.EndpointRequest>>;
export type EndpointResponseConforms = Assert<Conforms<'EndpointResponse', Mirror.EndpointResponse>>;
export type DeliveryResponseConforms = Assert<Conforms<'DeliveryResponse', Mirror.DeliveryResponse>>;
export type DeliveryAttemptResponseConforms = Assert<Conforms<'DeliveryAttemptResponse', Mirror.DeliveryAttemptResponse>>;
export type EventResponseConforms = Assert<Conforms<'EventResponse', Mirror.EventResponse>>;
export type DeliveryStatusCountsConforms = Assert<Conforms<'DeliveryStatusCounts', Mirror.DeliveryStatusCounts>>;
export type SubscriptionResponseConforms = Assert<Conforms<'SubscriptionResponse', Mirror.SubscriptionResponse>>;
export type McpConsentRequestResponseConforms = Assert<Conforms<'McpConsentRequestResponse', Mirror.McpConsentRequestResponse>>;
export type McpConsentApproveRequestConforms = Assert<Conforms<'McpConsentApproveRequest', Mirror.McpConsentApproveRequest>>;
export type McpConsentDecisionResponseConforms = Assert<Conforms<'McpConsentDecisionResponse', Mirror.McpConsentDecisionResponse>>;
export type McpGrantResponseConforms = Assert<Conforms<'McpGrantResponse', Mirror.McpGrantResponse>>;
export type IncomingSourceRequestConforms = Assert<Conforms<'IncomingSourceRequest', Mirror.IncomingSourceRequest>>;
export type IncomingSourceResponseConforms = Assert<Conforms<'IncomingSourceResponse', Mirror.IncomingSourceResponse>>;
export type IncomingDestinationRequestConforms = Assert<Conforms<'IncomingDestinationRequest', Mirror.IncomingDestinationRequest>>;
export type IncomingDestinationResponseConforms = Assert<Conforms<'IncomingDestinationResponse', Mirror.IncomingDestinationResponse>>;
export type IncomingEventResponseConforms = Assert<Conforms<'IncomingEventResponse', Mirror.IncomingEventResponse>>;
export type IncomingForwardAttemptResponseConforms = Assert<Conforms<'IncomingForwardAttemptResponse', Mirror.IncomingForwardAttemptResponse>>;
export type ReplayEventResponseConforms = Assert<Conforms<'ReplayEventResponse', Mirror.ReplayEventResponse>>;
export type DlqStatsResponseConforms = Assert<Conforms<'DlqStatsResponse', Mirror.DlqStatsResponse>>;
export type IncomingDlqItemResponseConforms = Assert<Conforms<'IncomingDlqItemResponse', Mirror.IncomingDlqItemResponse>>;
export type IncomingDlqRetryRequestConforms = Assert<Conforms<'IncomingDlqRetryRequest', Mirror.IncomingDlqRetryRequest>>;
export type IncomingBulkReplayRequestConforms = Assert<Conforms<'IncomingBulkReplayRequest', Mirror.IncomingBulkReplayRequest>>;
export type IncomingBulkReplayResponseConforms = Assert<Conforms<'IncomingBulkReplayResponse', Mirror.IncomingBulkReplayResponse>>;
export type TransformationRequestConforms = Assert<Conforms<'TransformationRequest', Mirror.TransformationRequest>>;
export type TransformationResponseConforms = Assert<Conforms<'TransformationResponse', Mirror.TransformationResponse>>;
export type TransformationVersionResponseConforms = Assert<Conforms<'TransformationVersionResponse', Mirror.TransformationVersionResponse>>;
export type TransformationVersionDiffResponseConforms = Assert<Conforms<'TransformationVersionDiffResponse', Mirror.TransformationVersionDiffResponse>>;
export type JsonDiffEntryConforms = Assert<Conforms<'JsonDiffEntry', Mirror.JsonDiffEntry>>;
export type ConsumerRequestConforms = Assert<Conforms<'ConsumerRequest', Mirror.ConsumerRequest>>;
export type ConsumerResponseConforms = Assert<Conforms<'ConsumerResponse', Mirror.ConsumerResponse>>;
export type PortalSessionRequestConforms = Assert<Conforms<'PortalSessionRequest', Mirror.PortalSessionRequest>>;
export type PortalSessionResponseConforms = Assert<Conforms<'PortalSessionResponse', Mirror.PortalSessionResponse>>;
export type PortalSessionInfoResponseConforms = Assert<Conforms<'PortalSessionInfoResponse', Mirror.PortalSessionInfoResponse>>;
export type PortalEndpointRequestConforms = Assert<Conforms<'PortalEndpointRequest', Mirror.PortalEndpointRequest>>;
export type PortalEndpointResponseConforms = Assert<Conforms<'PortalEndpointResponse', Mirror.PortalEndpointResponse>>;
export type PortalDeliveryResponseConforms = Assert<Conforms<'PortalDeliveryResponse', Mirror.PortalDeliveryResponse>>;

/** One concrete Page schema suffices: Spring builds them all from the same serializer. */
export type PageResponseConforms = Assert<Conforms<'PageEventResponse', Mirror.PageResponse<Mirror.EventResponse>>>;
