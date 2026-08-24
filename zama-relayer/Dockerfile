FROM node:24.19.0-alpine AS builder
WORKDIR /app
# python3/make/g++: some transitive deps (e.g. keccak) fall back to a native addon build on
# platforms without prebuilt binaries — alpine (musl) sometimes needs this. node-tfhe/tkms
# themselves ship precompiled .wasm, no native toolchain needed for those.
RUN apk add --no-cache python3 make g++
COPY package.json package-lock.json ./
RUN npm ci
COPY tsconfig.json ./
COPY src ./src
RUN npm run build

FROM node:24.19.0-alpine
WORKDIR /app
ENV NODE_ENV=production
COPY package.json package-lock.json ./
# npm is only an image-build tool. Keeping its full dependency tree in the running image added
# avoidable HIGH/CRITICAL advisories even though the relayer starts with plain `node`.
RUN apk add --no-cache python3 make g++ \
    && npm ci --omit=dev \
    && npm uninstall --global npm \
    && apk del python3 make g++
COPY --from=builder /app/dist ./dist
USER node
EXPOSE 3001
HEALTHCHECK --interval=15s --timeout=5s --retries=6 --start-period=20s \
    CMD wget -q -O- http://localhost:3001/health || exit 1
CMD ["node", "dist/index.js"]
