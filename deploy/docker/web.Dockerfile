# syntax=docker/dockerfile:1.10
# Образ веб-клиента: собранный SPA и маленький статический сервер на Go без оболочки.
#   docker build -f deploy/docker/web.Dockerfile -t easyitsm-web .
# API (/api) маршрутизирует Ingress напрямую в ядро, этот сервер отдаёт только статику.


FROM node:25-bookworm-slim AS spa
WORKDIR /src
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM golang:1.27-trixie AS server
WORKDIR /src
COPY deploy/docker/web/ ./
RUN CGO_ENABLED=0 go test ./... && CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /out/web-server .

FROM gcr.io/distroless/static-debian13:nonroot
ARG VERSION=dev
ARG REVISION=unknown
LABEL org.opencontainers.image.title="Easy ITSM web" \
      org.opencontainers.image.version="${VERSION}" \
      org.opencontainers.image.revision="${REVISION}" \
      org.opencontainers.image.source="https://github.com/GlebPutintsev/easyitsm" \
      org.opencontainers.image.vendor="Easy ITSM"
COPY --from=server /out/web-server /web-server
COPY --from=spa /src/dist /srv
USER 65532:65532
EXPOSE 8080
ENTRYPOINT ["/web-server", "-root", "/srv", "-addr", ":8080"]
