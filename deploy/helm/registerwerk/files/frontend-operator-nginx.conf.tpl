{{- /*
Templated copy of frontend-operator/nginx.conf, rendered via `tpl` from
templates/frontend-operator-configmap.yaml and mounted over the image's baked-in
/etc/nginx/conf.d/default.conf (COPYd there at image-build time by frontend-operator/Dockerfile).

Only the `location /api/` upstream differs from the real file, and only because it has to: the
Docker Compose version hardcodes `http://backend:8080` because Compose's embedded DNS resolves the
literal service name "backend" — but in this chart the backend Deployment's Service is named via
registerwerk.fullname (e.g. "<release>-registerwerk", not "backend"), which is only known at Helm
template time, not something the image built from the real nginx.conf could ever contain. Keep every
other line byte-for-byte identical to frontend-operator/nginx.conf — including the security headers,
duplicated per the comment below — so this doesn't silently drift from what's actually shipped and
verified in the Compose/local path.
*/ -}}
server {
    listen 80;
    server_tokens off;
    root /usr/share/nginx/html;
    index index.html;

    # Security headers — previously this nginx served the actual app shell (index.html + JS
    # bundles) with none at all. Kong's response-transformer plugin sets an equivalent set,
    # but only for /api/ JSON responses; the operator frontend bypasses Kong entirely (see
    # CLAUDE.md) and nothing else in its path ever added these to the page itself, where an
    # XSS/clickjacking payload would actually execute (ledger finding: "response hardening
    # headers are incomplete"). `always` applies them to error responses too.
    #
    # Defined here AND repeated (verbatim) inside the two location blocks below that set their
    # own Cache-Control: nginx's add_header only inherits into a location that declares none of
    # its own — a location with even one add_header drops every server-level one entirely. Since
    # nearly all real navigation ends up at `location = /index.html` via the SPA try_files
    # fallback, leaving these server-level-only would mean almost no real page load ever got
    # them. If you touch this set, update all three copies (server block below is kept so
    # any future location without its own add_header still inherits it).
    add_header X-Content-Type-Options "nosniff" always;
    add_header X-Frame-Options "DENY" always;
    add_header Referrer-Policy "strict-origin-when-cross-origin" always;
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    # style-src allows 'unsafe-inline' because Angular's [style.x] bindings set inline style
    # attributes at runtime. The Zama browser SDK compiles WebAssembly and talks directly to its
    # Sepolia relayer; 'wasm-unsafe-eval' enables only Wasm compilation (not JavaScript eval), and
    # connect-src is restricted to that exact service. Fonts are bundled into the image.
    add_header Content-Security-Policy "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; font-src 'self'; img-src 'self' data:; connect-src 'self' https://relayer.testnet.zama.org; worker-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'" always;

    location = /healthz {
        access_log off;
        return 204;
    }

    location /api/ {
        # Kubernetes Service DNS is stable for the pod's lifetime (unlike Compose's per-container
        # IP), so — unlike the Compose nginx.conf this file mirrors — there is no need for the
        # `resolver 127.0.0.11 valid=10s;` + `set $backend ...; proxy_pass $backend;` indirection
        # that forces per-request re-resolution there. A plain proxy_pass to the in-cluster
        # backend Service name is enough; kube-dns/CoreDNS updates the Service's own ClusterIP
        # mapping on endpoint changes, and rolling backend pods never changes the Service's
        # resolvable name or IP, only which pod IPs sit behind it.
        proxy_pass http://{{ include "registerwerk.fullname" . }}:{{ .Values.service.port }};
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
    }

    # Angular builds with outputHashing: "all", so bundle filenames change whenever their content
    # does and can safely be pinned. Without this nginx sent only ETag/Last-Modified, making the
    # browser issue a conditional GET for every chunk on every page load just to be told 304.
    location ~* \.(?:js|css|woff2|woff|ttf|eot|svg|png|jpg|jpeg|gif|ico)$ {
        # One combined header: `expires` would emit a second, separate Cache-Control alongside
        # this one, and duplicated Cache-Control headers are handled inconsistently by proxies.
        add_header Cache-Control "public, max-age=31536000, immutable";
        # Repeated from the server block above — see the comment there.
        add_header X-Content-Type-Options "nosniff" always;
        add_header X-Frame-Options "DENY" always;
        add_header Referrer-Policy "strict-origin-when-cross-origin" always;
        add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
        add_header Content-Security-Policy "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; font-src 'self'; img-src 'self' data:; connect-src 'self' https://relayer.testnet.zama.org; worker-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'" always;
        access_log off;
        try_files $uri =404;
    }

    # index.html is the one file that must NOT be cached — it references the hashed bundles, so a
    # stale copy pins the browser to a previous deployment.
    location = /index.html {
        add_header Cache-Control "no-cache";
        # Repeated from the server block above — see the comment there. This is the location
        # that actually matters most: the SPA fallback (`location /` below) internally
        # redirects here for almost every real navigation.
        add_header X-Content-Type-Options "nosniff" always;
        add_header X-Frame-Options "DENY" always;
        add_header Referrer-Policy "strict-origin-when-cross-origin" always;
        add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
        add_header Content-Security-Policy "default-src 'self'; script-src 'self' 'wasm-unsafe-eval'; style-src 'self' 'unsafe-inline'; font-src 'self'; img-src 'self' data:; connect-src 'self' https://relayer.testnet.zama.org; worker-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'" always;
    }

    location / {
        try_files $uri $uri/ /index.html;
    }

    # The stock image only gzips text/html. The Angular bundles are the bulk of the transfer.
    gzip on;
    gzip_vary on;
    gzip_min_length 1024;
    gzip_proxied any;
    gzip_types text/plain text/css application/javascript application/json
               application/manifest+json image/svg+xml application/xml;
}
