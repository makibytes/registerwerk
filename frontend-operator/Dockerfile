FROM node:22-alpine AS builder
WORKDIR /app/frontend-operator
COPY frontend-operator/package.json ./
RUN npm install --legacy-peer-deps
# shared UI library consumed via the "@registerwerk/ui" -> ../frontend-shared path alias
# in tsconfig.json; must sit next to this app in the build context, not inside it.
COPY frontend-shared/ /app/frontend-shared/
RUN ln -sfn /app/frontend-operator/node_modules /app/frontend-shared/node_modules
COPY frontend-operator/ ./
ARG BUILD_CONFIG=production
RUN npm run build -- --configuration=${BUILD_CONFIG}

FROM nginx:alpine
COPY --from=builder /app/frontend-operator/dist/registerwerk-operator/browser /usr/share/nginx/html
COPY frontend-operator/nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
