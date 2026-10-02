#!/usr/bin/env bash
#
# Copies the Linux products built by "mvn -f portfolio-app/pom.xml verify" into
# docker/dist/<docker architecture>, where the Dockerfile expects them.
#
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PRODUCTS="$REPO/portfolio-product/target/products/name.abuchen.portfolio.product/linux/gtk"
DIST="$REPO/docker/dist"

rm -rf "$DIST"

# Tycho architecture -> Docker architecture (TARGETARCH)
for pair in x86_64:amd64 aarch64:arm64; do
    tycho="${pair%%:*}"
    docker="${pair##*:}"
    source="$PRODUCTS/$tycho/portfolio"

    if [[ ! -x "$source/PortfolioPerformance" ]]; then
        echo "missing $source/PortfolioPerformance - build the product first:" >&2
        echo "  mvn -f portfolio-app/pom.xml verify -DskipTests" >&2
        exit 1
    fi

    mkdir -p "$DIST/$docker"
    cp -a "$source/." "$DIST/$docker/"
    echo "staged $tycho as $DIST/$docker"
done
