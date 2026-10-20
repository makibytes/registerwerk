FROM node:26.12.0-alpine AS builder
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

FROM node:26.12.0-alpine
WORKDIR /app
ENV NODE_ENV=production
COPY package.json package-lock.json ./
# npm is only an image-build tool. Keeping its full dependency tree in the running image added
# avoidable HIGH/CRITICAL advisories even though the relayer starts with plain `node`.
# The pinned node tag can lag patched Alpine packages (the Trivy gate in CI fails on a fixable
# OpenSSL HIGH otherwise), so the runtime layer is upgraded during the build, as backend/Dockerfile does.
# viem lists `typescript` as an (optional) peer and npm keeps it when it is in the tree, even with
# --omit=dev. Since TypeScript 7 that package ships a native compiler written in Go, whose standard
# library carries its own HIGH advisories; nothing here compiles at run time, so it is removed.
RUN apk upgrade --no-cache \
    && apk add --no-cache python3 make g++ \
    && npm ci --omit=dev \
    && npm uninstall --global npm \
    && apk del python3 make g++ \
    && rm -rf node_modules/typescript node_modules/@typescript
COPY --from=builder /app/dist ./dist
USER node
EXPOSE 3001
HEALTHCHECK --interval=15s --timeout=5s --retries=6 --start-period=20s \
    CMD wget -q -O- http://localhost:3001/health || exit 1
CMD ["node", "dist/index.js"]
