#!/bin/sh
# Turns a built marketing document root into the staging copy of the site.
# Run by the Dockerfile when SITE_ENV=staging; a production build never calls it,
# so what ships to extrablatt.app is the file as it is committed.
#
# Two things have to change, and both matter more than they look.
#
# The hostname, because every link and every mention of the app on the page is
# the same string. A staging copy still pointing at production would hand
# someone who clicked "Choose yearly" to the real checkout and charge them.
#
# Indexability, because this is a second public copy of the landing page, word
# for word. Left crawlable it competes with the real one for the same searches.
# robots.txt asks crawlers not to fetch it; the meta tag is what keeps it out of
# an index anyway when something links to it, which robots.txt alone does not.
set -eu

root=${1:?usage: make-staging <document-root>}
app_host=${STAGING_APP_HOST:-staging.extrablatt.app}
page="$root/index.html"

[ -f "$page" ] || { echo "make-staging: no index.html under $root" >&2; exit 1; }

# Each substitution stays on one line: BusyBox sed, which is what the Caddy
# image ships, does not read a newline escape in a replacement the way GNU does.
sed -i "s/reader\.extrablatt\.app/$app_host/g" "$page"

sed -i "s|<title>|<meta name=\"robots\" content=\"noindex, nofollow\"/><title>[staging] |" "$page"

banner='<div style="background:#8a1c1c;color:#fff;padding:0.6rem 1rem;text-align:center;font:600 0.95rem/1.4 system-ui,-apple-system,sans-serif">Staging preview, not the live site. Everything here points at <code style="font:inherit">'"$app_host"'</code>.</div>'
sed -i "s|<body>|<body>$banner|" "$page"

printf 'User-agent: *\nDisallow: /\n' > "$root/robots.txt"

# Fail loudly rather than shipping a staging site that still links to production.
if grep -q "reader\.extrablatt\.app" "$page"; then
    echo "make-staging: production hostname still present in $page" >&2
    exit 1
fi
