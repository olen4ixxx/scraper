FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY . .
RUN sh ./gradlew :app:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /workspace/app/build/libs/app-1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "--enable-preview", "-jar", "app.jar"]
