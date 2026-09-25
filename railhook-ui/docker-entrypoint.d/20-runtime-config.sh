#!/bin/sh
# Per-container settings, written to /tmp because the root fs is read-only.
# A bad value is dropped with a warning, never fails startup.
set -eu

OUT="${RAILHOOK_RUNTIME_CONFIG_OUT:-/tmp/railhook-config.js}"
SITE_CONF_OUT="${RAILHOOK_SITE_CONF_OUT:-/tmp/railhook-site.conf}"
DEFAULT_CAPTCHA_SCRIPT_URL='https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit'

trim() {
    printf '%s' "$1" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'
}

# Patterns exclude quotes, backslashes, ; and whitespace, so a value can't escape JSON or nginx.
matches() {
    [ -n "$1" ] && printf '%s' "$1" | grep -Eq "$2" && [ "$(printf '%s' "$1" | wc -l)" -eq 0 ]
}

domain=$(trim "${RAILHOOK_CONTACT_DOMAIN:-}")
if [ -n "$domain" ] && ! matches "$domain" '^[A-Za-z0-9.-]+$'; then
    echo "20-runtime-config: RAILHOOK_CONTACT_DOMAIN is not a hostname (letters, digits, dots and hyphens only); offering no contact addresses" >&2
    domain=""
fi

# An origin only: a path would be doubled by every URL built on it.
site=$(trim "${RAILHOOK_SITE_URL:-}" | sed -e 's:/*$::')
if [ -n "$site" ] && ! matches "$site" '^https?://[A-Za-z0-9.-]+(:[0-9]+)?$'; then
    echo "20-runtime-config: RAILHOOK_SITE_URL is not an origin like https://hooks.example.com; publishing relative URLs instead" >&2
    site=""
fi

captcha_key=$(trim "${RAILHOOK_CAPTCHA_SITE_KEY:-}")
if [ -n "$captcha_key" ] && ! matches "$captcha_key" '^[A-Za-z0-9_-]+$'; then
    echo "20-runtime-config: RAILHOOK_CAPTCHA_SITE_KEY has characters no site key uses; the registration challenge is off" >&2
    captcha_key=""
fi

captcha_script=""
if [ -n "$captcha_key" ]; then
    captcha_script=$(trim "${RAILHOOK_CAPTCHA_SCRIPT_URL:-}")
    [ -n "$captcha_script" ] || captcha_script="$DEFAULT_CAPTCHA_SCRIPT_URL"
    if ! matches "$captcha_script" '^https://[A-Za-z0-9._~:/?#@!$&()*+,=%-]+$'; then
        echo "20-runtime-config: RAILHOOK_CAPTCHA_SCRIPT_URL is not a plain https URL; the registration challenge is off" >&2
        captcha_key=""
        captcha_script=""
    fi
fi

analytics_token=$(trim "${RAILHOOK_WEB_ANALYTICS_TOKEN:-}")
if [ -n "$analytics_token" ] && ! matches "$analytics_token" '^[A-Za-z0-9]+$'; then
    echo "20-runtime-config: RAILHOOK_WEB_ANALYTICS_TOKEN has characters no token uses; web analytics is off" >&2
    analytics_token=""
fi

status_url=$(trim "${RAILHOOK_STATUS_PAGE_URL:-}")
if [ -n "$status_url" ] && ! matches "$status_url" '^https://[A-Za-z0-9._~:/?#@!$&()*+,=%-]+$'; then
    echo "20-runtime-config: RAILHOOK_STATUS_PAGE_URL is not a plain https URL; the status link is off" >&2
    status_url=""
fi

public_tester=false
if [ "$(trim "${RAILHOOK_PUBLIC_TESTER:-}")" = "true" ]; then
    public_tester=true
fi

public_demo=false
if [ "$(trim "${RAILHOOK_PUBLIC_DEMO:-}")" = "true" ]; then
    public_demo=true
fi

public_blog=false
blog_nginx=off
if [ "$(trim "${RAILHOOK_PUBLIC_BLOG:-}")" = "true" ]; then
    public_blog=true
    blog_nginx=on
fi

cat > "$OUT" <<CONF
window.__RAILHOOK__ = {"contactDomain": "${domain}", "siteUrl": "${site}", "captchaSiteKey": "${captcha_key}", "captchaScriptUrl": "${captcha_script}", "webAnalyticsToken": "${analytics_token}", "statusPageUrl": "${status_url}", "publicTester": ${public_tester}, "publicDemo": ${public_demo}, "publicBlog": ${public_blog}};
CONF

cat > "$SITE_CONF_OUT" <<CONF
set \$railhook_site_url "${site}";
set \$railhook_blog "${blog_nginx}";
CONF

if [ -n "$domain" ]; then
    echo "20-runtime-config: contact addresses on ${domain}"
else
    echo "20-runtime-config: no contact domain, contact addresses hidden"
fi
if [ -n "$site" ]; then
    echo "20-runtime-config: public origin ${site}"
else
    echo "20-runtime-config: no public origin, the pages publish relative URLs"
fi
if [ -n "$captcha_key" ]; then
    echo "20-runtime-config: captcha on"
else
    echo "20-runtime-config: captcha off"
fi
if [ -n "$analytics_token" ]; then
    echo "20-runtime-config: web analytics on"
else
    echo "20-runtime-config: web analytics off"
fi
if [ "$public_blog" = true ]; then
    echo "20-runtime-config: blog on"
else
    echo "20-runtime-config: blog off"
fi
