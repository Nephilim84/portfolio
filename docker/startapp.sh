#!/bin/sh
#
# Started by the base image as the application user, with HOME=/config.
#
set -eu

# The REST API listens on 127.0.0.1:5712 inside the container only, and a port
# published with "docker run -p" cannot reach the loopback interface. Forward
# the container's own address to it. The API still checks that the Host header
# names a loopback address, so clients must call it as http://127.0.0.1:5712 or
# http://localhost:5712 (or send such a Host header explicitly).
CONTAINER_IP="$(hostname -i | awk '{print $1}')"
socat "TCP-LISTEN:5712,bind=${CONTAINER_IP},fork,reuseaddr" TCP:127.0.0.1:5712 &

exec /opt/portfolio/PortfolioPerformance -data /config/workspace
