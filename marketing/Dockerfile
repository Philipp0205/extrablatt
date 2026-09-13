# Serves the static landing page on Railway (or any platform that provides a
# $PORT to bind to). For the self-hosted VPS/Caddy setup, see deploy/Caddyfile
# instead. That one fronts both this site and the app and handles its own TLS.
#
# Copies files by name (rather than `COPY .`) so the Dockerfile and Caddyfile
# themselves are never part of the served document root.
FROM caddy:2-alpine

# Which copy of the site this image is. Left alone it builds the live site.
# The staging marketing service sets SITE_ENV=staging as a Railway variable,
# which Railway passes through to the build, and make-staging.sh then retargets
# every link at the staging app and tells crawlers to leave the copy alone.
ARG SITE_ENV=production
ARG STAGING_APP_HOST=staging.extrablatt.app

COPY Caddyfile /etc/caddy/Caddyfile
COPY index.html /srv/marketing/index.html
COPY assets /srv/marketing/assets

# Kept out of the document root: it is build tooling, not part of the site.
COPY make-staging.sh /usr/local/bin/make-staging
RUN chmod +x /usr/local/bin/make-staging \
    && if [ "$SITE_ENV" = "staging" ]; then STAGING_APP_HOST="$STAGING_APP_HOST" make-staging /srv/marketing; fi
