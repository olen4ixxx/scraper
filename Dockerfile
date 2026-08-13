FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN sh ./gradlew :app:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/app/build/libs/app-1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
# The JVM's default heap sizing ignores how little memory a small hosting plan actually
# gives the container, so it happily grows until the platform kills it. This keeps the heap
# a share of whatever the container is allotted, locally or hosted.
ENTRYPOINT ["java", "--enable-preview", "-XX:MaxRAMPercentage=70", "-jar", "app.jar"]
