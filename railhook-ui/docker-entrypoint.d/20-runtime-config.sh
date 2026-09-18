#!/bin/sh
# Write the settings that belong to this container rather than to the image.
#
# The published image is built once for every deployment of it: the hosted cloud and each
# self-hosted install run the same bytes. Whatever must differ between them — the public
# origin, the registration challenge, the domain behind the sales@ and support@ addresses, the
# web analytics token —
# therefore cannot live in the bundle. It is written here, when the container starts:
#
#   /tmp/railhook-config.js  window.__RAILHOOK__, which nginx serves as /config.js ahead of the app
#                            and of the docs
#   /tmp/railhook-site.conf  $railhook_site_url, which nginx substitutes for the placeholder
#                            origin the build leaves in the HTML, sitemap and robots.txt
#
# /tmp for the same reason as the resolver file: the chart runs this pod with a read-only
# root filesystem and mounts /tmp writable.
#
# Both files are always written, with empty values when nothing is set, so /config.js never
# 404s and nginx never includes a file that is not there. A bad value is dropped with a
# warning rather than failing the container: a typo in a setting is not a reason to take the
# dashboard down.
set -eu

OUT="${RAILHOOK_RUNTIME_CONFIG_OUT:-/tmp/railhook-config.js}"
SITE_CONF_OUT="${RAILHOOK_SITE_CONF_OUT:-/tmp/railhook-site.conf}"
DEFAULT_CAPTCHA_SCRIPT_URL='https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit'

trim() {
    printf '%s' "$1" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//'
}

# Keeps a value only when all of it matches the pattern. Every pattern below excludes quotes,
# backslashes, semicolons and whitespace, so a kept value cannot close the JSON string or the
# nginx directive it is written into.
matches() {
    [ -n "$1" ] && printf '%s' "$1" | grep -Eq "$2" && [ "$(printf '%s' "$1" | wc -l)" -eq 0 ]
}

domain=$(trim "${RAILHOOK_CONTACT_DOMAIN:-}")
if [ -n "$domain" ] && ! matches "$domain" '^[A-Za-z0-9.-]+$'; then
    echo "20-runtime-config: RAILHOOK_CONTACT_DOMAIN is not a hostname (letters, digits, dots and hyphens only); offering no contact addresses" >&2
    domain=""
fi

# An origin and nothing more: scheme, host, optional port. A path would be doubled by every
# URL the app appends one to.
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

# Cloudflare Web Analytics: cookieless, so no consent banner. Off unless set, which is every
# self-hosted install — they report to nobody. The token is public (it ends up in the page), but
# it is still kept out of the log, like the site key.
analytics_token=$(trim "${RAILHOOK_WEB_ANALYTICS_TOKEN:-}")
if [ -n "$analytics_token" ] && ! matches "$analytics_token" '^[A-Za-z0-9]+$'; then
    echo "20-runtime-config: RAILHOOK_WEB_ANALYTICS_TOKEN has characters no token uses; web analytics is off" >&2
    analytics_token=""
fi

cat > "$OUT" <<CONF
window.__RAILHOOK__ = {"contactDomain": "${domain}", "siteUrl": "${site}", "captchaSiteKey": "${captcha_key}", "captchaScriptUrl": "${captcha_script}", "webAnalyticsToken": "${analytics_token}"};
CONF

cat > "$SITE_CONF_OUT" <<CONF
set \$railhook_site_url "${site}";
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
