FROM maven:3-eclipse-temurin-17-alpine AS build
WORKDIR /build
COPY server/pom.xml .
COPY server/src ./src
RUN mvn package -DskipTests -q

FROM eclipse-temurin:17-jre-alpine
RUN apk add --no-cache ca-certificates tzdata
WORKDIR /app
COPY --from=build /build/target/server.jar .
COPY docker/docker-entrypoint.sh /usr/local/bin/
RUN chmod +x /usr/local/bin/docker-entrypoint.sh
EXPOSE 25565 4433/udp 8088
VOLUME ["/app/data"]
ENTRYPOINT ["docker-entrypoint.sh"]
