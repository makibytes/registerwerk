FROM node:22-alpine AS builder
WORKDIR /app
COPY package.json ./
RUN npm install --legacy-peer-deps
COPY . .
ARG BUILD_CONFIG=production
RUN npm run build -- --configuration=${BUILD_CONFIG}

FROM nginx:alpine
COPY --from=builder /app/dist/registerwerk-operator/browser /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
