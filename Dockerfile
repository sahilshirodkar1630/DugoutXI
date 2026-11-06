# The backend as a container, for Render (see render.yaml and docs/DEPLOY.md) or any Docker host.
#
# Two stages: Maven builds the jar, then only a Java runtime and the jar go into the image. The JVM settings keep it
# inside a free host's 512 MB: heap at 60% of the container's memory, the serial collector (one thread, least
# overhead on a tenth of a CPU), smaller thread stacks and a capped code cache.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 dugout
COPY --from=build /build/target/*.jar app.jar
USER dugout
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=60 -XX:+UseSerialGC -Xss512k -XX:ReservedCodeCacheSize=64m -XX:MaxMetaspaceSize=160m -XX:TieredStopAtLevel=1 -Duser.timezone=UTC"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
