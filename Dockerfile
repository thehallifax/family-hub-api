# Stage 1: Build the JAR
FROM eclipse-temurin:21@sha256:92a2a4d7a928d057e7bd999c418d66c26a34eb9a0442f3ab67721c3f88110b2d AS build
WORKDIR /app

# Copy mvn wrapper from repo (one source of truth)
COPY .mvn .mvn

# Copy pom.xml to cache dependency downloads.
COPY mvnw pom.xml ./

# Docker caches each layer — if pom.xml hasn't changed,
# Maven won't re-download dependencies on subsequent builds.
RUN ./mvnw dependency:go-offline -B

# Now copy source and build
COPY src ./src
RUN ./mvnw clean package -DskipTests -B

# Stage 2: Run (JRE only, no Maven or source code)
FROM eclipse-temurin:21-jre@sha256:49e21e16e3c86eb7816a44a67549910ed090fbeb40c29c525d58bf5e02e91b0f
WORKDIR /app
# create a system user with no shell
RUN useradd -r -s /bin/false appuser
RUN mkdir -p /app/media && chown appuser:appuser /app/media
# from pom.xml :: <finalName>app</finalName>
COPY --from=build /app/target/app.jar app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", "-Xmx256m", "-Xms128m", "-jar", "app.jar"]
