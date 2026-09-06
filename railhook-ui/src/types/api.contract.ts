/**
 * Compile-time conformance between the hand-written mirror and the committed OpenAPI spec.
 *
 * `api.types.ts` is shaped for how the UI consumes the API; `api.generated.ts` is the spec,
 * verbatim. They cannot simply be swapped for one another — springdoc marks nothing `required`,
 * so every generated property is optional, and consuming those types directly would put a
 * null-check on every field read in the app. What this file does instead is make the mirror
 * *checked*: each interface below must still be assignable to its schema, so `tsc --noEmit` — a
 * CI gate already — fails the moment the two disagree.
 *
 * What that catches, which nothing caught before:
 *
 * - a field renamed or removed on the backend: the mirror's key is no longer a key of the schema;
 * - a field retyped (`string` → `number`, object → array): the property types stop matching;
 * - the mirror inventing a field the API never returns.
 *
 * What it deliberately allows: the mirror being *narrower* than the spec. `role` is
 * `'OWNER' | 'DEVELOPER' | 'VIEWER'` here while the spec also lists `API_KEY`, because an API key
 * never signs in to the dashboard. Narrowing is assignable; widening is not, which is the useful
 * direction — a mirror that says `string` where the spec says an enum has silently lost the
 * enum.
 *
 * It does not catch a field *added* to a DTO and never mirrored. That is a smaller failure (a
 * feature the UI does not use yet, rather than a value that reads `undefined`) and catching it
 * would mean requiring the mirror to be exhaustive, which it is deliberately not.
 *
 * Regenerate with `npm run types:generate` after any backend DTO change; `make types-check`
 * fails on a stale `api.generated.ts`.
 */
import type { components } from './api.generated';
import type * as Mirror from './api.types';

type Schemas = components['schemas'];

/**
 * Strips `null` and `undefined` everywhere, so the comparison is about names and shapes rather
 * than about optionality. springdoc marks nothing required and nothing nullable, so neither side
 * carries usable optionality information; the mirror's own `| null`s are what the UI wants and
 * are not drift.
 */
type DeepDenull<T> =
    T extends (infer U)[] ? DeepDenull<U>[]
        : T extends object ? { [K in keyof T]-?: DeepDenull<NonNullable<T[K]>> }
            : T;

/** `true` when every key of `M` is a key of the schema and every property type is compatible. */
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
export type LoginRequestConforms = Assert<Conforms<'LoginRequest', Mirror.LoginRequest>>;
export type UserResponseConforms = Assert<Conforms<'UserResponse', Mirror.UserResponse>>;
export type CurrentUserResponseConforms = Assert<Conforms<'CurrentUserResponse', Mirror.CurrentUserResponse>>;
export type OrganizationResponseConforms = Assert<Conforms<'OrganizationResponse', Mirror.OrganizationResponse>>;
export type ProjectRequestConforms = Assert<Conforms<'ProjectRequest', Mirror.ProjectRequest>>;
export type ProjectResponseConforms = Assert<Conforms<'ProjectResponse', Mirror.ProjectResponse>>;
export type EndpointRequestConforms = Assert<Conforms<'EndpointRequest', Mirror.EndpointRequest>>;
export type EndpointResponseConforms = Assert<Conforms<'EndpointResponse', Mirror.EndpointResponse>>;
export type DeliveryResponseConforms = Assert<Conforms<'DeliveryResponse', Mirror.DeliveryResponse>>;
export type DeliveryAttemptResponseConforms = Assert<Conforms<'DeliveryAttemptResponse', Mirror.DeliveryAttemptResponse>>;
export type EventResponseConforms = Assert<Conforms<'EventResponse', Mirror.EventResponse>>;
export type SubscriptionResponseConforms = Assert<Conforms<'SubscriptionResponse', Mirror.SubscriptionResponse>>;
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

/**
 * `PageResponse<T>` is generic; springdoc emits one concrete `PageXxx` schema per payload type.
 * Checking it against a representative one is enough — Spring builds them all from the same
 * `Page` serializer, so they cannot drift from each other, only together.
 */
export type PageResponseConforms = Assert<Conforms<'PageEventResponse', Mirror.PageResponse<Mirror.EventResponse>>>;
