FROM --platform=$BUILDPLATFORM golang:1.24.6-bookworm AS minio-build
WORKDIR /src
# The former public image is unavailable. Build the same release from verified upstream commits.
RUN git clone --depth 1 --branch RELEASE.2025-09-07T16-13-09Z https://github.com/minio/minio.git minio \
    && test "$(git -C minio rev-parse HEAD)" = 07c3a429bfed433e49018cb0f78a52145d4bedeb
WORKDIR /src/minio
ARG TARGETOS
ARG TARGETARCH
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    mkdir /out \
    && CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH go build -tags kqueue -trimpath \
        -ldflags "$(MINIO_RELEASE=RELEASE go run buildscripts/gen-ldflags.go)" -o /out/minio \
    && git archive --format=tar.gz --output=/out/minio-source.tar.gz HEAD

FROM --platform=$BUILDPLATFORM golang:1.24.6-bookworm AS mc-build
WORKDIR /src
RUN git clone --depth 1 --branch RELEASE.2025-08-13T08-35-41Z https://github.com/minio/mc.git mc \
    && test "$(git -C mc rev-parse HEAD)" = 7394ce0dd2a80935aded936b09fa12cbb3cb8096
WORKDIR /src/mc
ARG TARGETOS
ARG TARGETARCH
RUN --mount=type=cache,target=/go/pkg/mod --mount=type=cache,target=/root/.cache/go-build \
    mkdir /out \
    && CGO_ENABLED=0 GOOS=$TARGETOS GOARCH=$TARGETARCH go build -trimpath \
        -ldflags "$(MC_RELEASE=RELEASE go run buildscripts/gen-ldflags.go)" -o /out/mc \
    && git archive --format=tar.gz --output=/out/mc-source.tar.gz HEAD

FROM debian:bookworm-slim
RUN apt-get update && apt-get install --no-install-recommends --yes ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY --from=minio-build /out/minio /usr/bin/minio
COPY --from=mc-build /out/mc /usr/bin/mc
COPY --from=minio-build /src/minio/dockerscripts/docker-entrypoint.sh /usr/bin/docker-entrypoint.sh
COPY --from=minio-build /src/minio/LICENSE /out/minio-source.tar.gz /usr/share/minio/
COPY --from=mc-build /src/mc/LICENSE /out/mc-source.tar.gz /usr/share/mc/
ENV MC_CONFIG_DIR=/tmp/.mc
ENTRYPOINT ["/usr/bin/docker-entrypoint.sh"]
CMD ["minio"]
