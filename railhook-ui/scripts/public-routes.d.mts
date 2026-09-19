/**
 * Types for `public-routes.mjs`, which stays untyped JavaScript because two Node scripts run it
 * directly. `src/__tests__/publicRoutes.test.ts` imports it to check that the blog's posts really
 * are enumerated, and without this the import is an implicit `any` the build refuses.
 */
export declare function publicRoutes(): { path: string; priority: string; changefreq: string }[];
