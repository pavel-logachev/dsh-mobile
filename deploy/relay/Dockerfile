# syntax=docker/dockerfile:1
# Verified Docker Hub multi-platform manifest for node:24.21.0-bookworm-slim.
FROM node:24.21.0-bookworm-slim@sha256:0e0ff40c39bc087845bfb27465a0df4ea419520094bc35842ff83dd8cbe6f9b6 AS build
WORKDIR /app
COPY --link relay/package.json relay/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci
COPY --link relay/tsconfig.json ./tsconfig.json
COPY --link relay/src ./src
RUN npm run build

FROM node:24.21.0-bookworm-slim@sha256:0e0ff40c39bc087845bfb27465a0df4ea419520094bc35842ff83dd8cbe6f9b6 AS runtime-deps
WORKDIR /app
COPY --link relay/package.json relay/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --omit=dev

FROM node:24.21.0-bookworm-slim@sha256:0e0ff40c39bc087845bfb27465a0df4ea419520094bc35842ff83dd8cbe6f9b6 AS runtime
ENV NODE_ENV=production
WORKDIR /app
COPY --from=runtime-deps --link --chown=10001:10001 /app/node_modules ./node_modules
COPY --from=runtime-deps --link --chown=10001:10001 /app/package.json ./package.json
COPY --from=build --link --chown=10001:10001 /app/dist ./dist
RUN mkdir -p /data && chown 10001:10001 /data
USER 10001:10001
EXPOSE 8088
LABEL org.opencontainers.image.title="DSH Mobile Relay" \
      org.opencontainers.image.description="Isolated opaque TLS relay for DSH Mobile" \
      org.opencontainers.image.base.name="node:24.21.0-bookworm-slim@sha256:0e0ff40c39bc087845bfb27465a0df4ea419520094bc35842ff83dd8cbe6f9b6"
ENTRYPOINT ["node", "dist/cli.js"]
CMD ["serve", "--state", "/data/relay.sqlite", "--bind", "0.0.0.0", "--port", "8088", "--external-tls"]
