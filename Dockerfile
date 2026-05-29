FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src

COPY server/pom.xml server/pom.xml
RUN mvn -f server/pom.xml -q -DskipTests dependency:go-offline

COPY server server
RUN mvn -f server/pom.xml -q -DskipTests package

FROM eclipse-temurin:17-jre
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
      ca-certificates \
      curl \
      iproute2 \
      iptables \
      redsocks \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /src/server/target/server.jar /app/server.jar
COPY docker/volter-entrypoint.sh /usr/local/bin/volter-entrypoint.sh
COPY docker/redsocks.conf /etc/redsocks.conf

RUN chmod +x /usr/local/bin/volter-entrypoint.sh \
    && mkdir -p /app/certs /app/data

EXPOSE 25565/tcp 4433/udp 8088/tcp
ENTRYPOINT ["/usr/local/bin/volter-entrypoint.sh"]
