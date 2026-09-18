// Cloudflare Web Analytics, loaded by the app (index.html) and by the docs site
// (railhook-docs/astro.config.mjs), after /config.js.
//
// The token is runtime config (RAILHOOK_WEB_ANALYTICS_TOKEN on the UI container), so the one
// published image stays silent on every self-hosted install and reports only where a token was
// set. Cookieless, and it follows client-side navigation on its own. Never inside /portal: that
// page is embedded in someone else's product, and its visitors are theirs. Never for a visitor who
// declined it in the cookie notice (src/lib/consent.ts writes the key).
(function () {
  var token = window.__RAILHOOK__ && window.__RAILHOOK__.webAnalyticsToken;
  if (!token || /^\/portal(\/|$)/.test(window.location.pathname)) return;
  try {
    if (window.localStorage.getItem('railhook.consent') === 'declined') return;
  } catch (e) {
    // Storage blocked: no answer on record, so the default stands.
  }
  var beacon = document.createElement('script');
  beacon.defer = true;
  beacon.src = 'https://static.cloudflareinsights.com/beacon.min.js';
  beacon.setAttribute('data-cf-beacon', JSON.stringify({ token: token }));
  document.head.appendChild(beacon);
})();
