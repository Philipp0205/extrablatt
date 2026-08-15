# Serves the static landing page on Railway (or any platform that provides a
# $PORT to bind to). For the self-hosted VPS/Caddy setup, see deploy/Caddyfile
# instead — that one fronts both this site and the app and handles its own TLS.
#
# Copies files by name (rather than `COPY .`) so the Dockerfile and Caddyfile
# themselves are never part of the served document root.
FROM caddy:2-alpine
COPY Caddyfile /etc/caddy/Caddyfile
COPY index.html /srv/marketing/index.html
COPY assets /srv/marketing/assets
