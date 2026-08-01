FROM node:24.18.1-alpine AS builder
WORKDIR /app/frontend-customer
# Lockfile copied alongside package.json so the build can use `npm ci` — `npm install` re-resolved
# dependency ranges at build time, which meant two builds of the same commit could ship different
# transitive versions.
COPY frontend-customer/package.json frontend-customer/package-lock.json ./
RUN npm ci --legacy-peer-deps
# shared UI library consumed via the "@registerwerk/ui" -> ../frontend-shared path alias
# in tsconfig.json; must sit next to this app in the build context, not inside it.
COPY frontend-shared/ /app/frontend-shared/
RUN ln -sfn /app/frontend-customer/node_modules /app/frontend-shared/node_modules
COPY frontend-customer/ ./
ARG BUILD_CONFIG=production
RUN npm run build -- --configuration=${BUILD_CONFIG}

FROM nginx:1.30.4-alpine
# nginx defaults worker_processes to "auto" — one per host CPU, so an 8-core demo host ran 8
# workers here, 8 more in the operator frontend and 8 in Kong. Serving a static SPA needs nowhere
# near that, and the process list it produced made an unrelated CPU problem look like an nginx one.
RUN sed -i 's/^worker_processes.*/worker_processes 2;/' /etc/nginx/nginx.conf
COPY --from=builder /app/frontend-customer/dist/registerwerk-customer/browser /usr/share/nginx/html
COPY frontend-customer/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
