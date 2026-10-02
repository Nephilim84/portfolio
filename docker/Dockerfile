# Portfolio Performance in a container: the desktop application on a virtual
# display, viewable in a browser (noVNC, port 5800), with the local REST API
# forwarded to port 5712.
#
# The application is not built here. Build the product first and stage it per
# architecture - the GitHub workflow .github/workflows/docker.yml does both:
#
#   mvn -f portfolio-app/pom.xml verify -DskipTests
#   docker/stage-product.sh            # copies the product into docker/dist/<arch>
#   docker build -t portfolio-performance docker
#
# Run (both ports published on the host's loopback only):
#
#   docker run -d --name portfolio \
#     -p 127.0.0.1:5800:5800 -p 127.0.0.1:5712:5712 \
#     -v portfolio-config:/config -v ~/portfolio:/storage \
#     ghcr.io/<owner>/portfolio-performance:latest

# a full JRE: the launcher passes --add-modules=ALL-SYSTEM
FROM eclipse-temurin:21-jre AS jre

FROM jlesage/baseimage-gui:debian-12-v4

ARG TARGETARCH

# SWT on GTK 3, WebKit for the embedded browser, fonts, and socat for the
# REST API forwarder
RUN add-pkg \
        libgtk-3-0 \
        libxtst6 \
        libwebkit2gtk-4.1-0 \
        fonts-dejavu \
        fonts-liberation \
        socat

COPY --from=jre /opt/java/openjdk /opt/java/openjdk

COPY dist/${TARGETARCH}/ /opt/portfolio/

# the Eclipse launcher picks up a "jre" folder next to the executable
RUN ln -s /opt/java/openjdk /opt/portfolio/jre \
    && chmod -R a+rX /opt/portfolio

COPY startapp.sh /startapp.sh
RUN chmod +x /startapp.sh

RUN set-cont-env APP_NAME "Portfolio Performance"

# /config holds the workspace (preferences, the REST API's enabled files and
# authorized clients); /storage is meant for the portfolio files themselves
VOLUME ["/config", "/storage"]

# 5800: the application in the browser (noVNC), 5712: the REST API
EXPOSE 5800 5712
