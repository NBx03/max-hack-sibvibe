# Реестр базовых образов. По умолчанию public.ecr.aws; если он недоступен или ограничил трафик:
#   BASE_REGISTRY=mirror.gcr.io/library docker compose up --build
# Образы закреплены по digest, у зеркал он тот же.
ARG BASE_REGISTRY=public.ecr.aws/docker/library

# ---------- Этап 1: зависимости ----------
# Отдельный слой под манифесты. npm ci ставит строго по package-lock.json -
# воспроизводимая сборка, а не "что сегодня в реестре".
FROM ${BASE_REGISTRY}/node:22-alpine@sha256:0a7108bf6c7bf5de370ffb1a3ed6be93d405b43ff159f681a8d18c0e2bc2e402 AS deps
WORKDIR /app
COPY package.json package-lock.json ./
RUN --mount=type=cache,target=/root/.npm \
    npm ci --no-audit --no-fund

# ---------- Этап 2: сборка ----------
FROM ${BASE_REGISTRY}/node:22-alpine@sha256:0a7108bf6c7bf5de370ffb1a3ed6be93d405b43ff159f681a8d18c0e2bc2e402 AS build
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN npm run build

# ---------- Этап 3: раздача статики ----------
FROM ${BASE_REGISTRY}/nginx:1.27-alpine@sha256:65645c7bb6a0661892a8b03b89d0743208a18dd2f3f17a54ef4b76fb8e2f2a10 AS runtime

COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
    CMD wget -qO- http://127.0.0.1/ >/dev/null 2>&1 || exit 1

CMD ["nginx", "-g", "daemon off;"]
