FROM node:24.18.1-alpine AS builder
WORKDIR /app
# python3/make/g++: some transitive deps (e.g. keccak) fall back to a native addon build on
# platforms without prebuilt binaries — alpine (musl) sometimes needs this. node-tfhe/tkms
# themselves ship precompiled .wasm, no native toolchain needed for those.
RUN apk add --no-cache python3 make g++
COPY package.json ./
RUN npm install
COPY tsconfig.json ./
COPY src ./src
RUN npm run build

FROM node:24.18.1-alpine
WORKDIR /app
ENV NODE_ENV=production
COPY package.json ./
RUN apk add --no-cache python3 make g++ \
    && npm install --omit=dev \
    && apk del python3 make g++
COPY --from=builder /app/dist ./dist
EXPOSE 3001
HEALTHCHECK --interval=15s --timeout=5s --retries=6 --start-period=20s \
    CMD wget -q -O- http://localhost:3001/health || exit 1
CMD ["node", "dist/index.js"]
