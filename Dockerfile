FROM gradle:9.1.0-jdk24-alpine AS build

WORKDIR /home/gradle/project

COPY --chown=gradle:gradle gradle/libs.versions.toml gradle/libs.versions.toml
COPY --chown=gradle:gradle build.gradle.kts .
COPY --chown=gradle:gradle settings.gradle.kts .
COPY --chown=gradle:gradle gradle.properties .
COPY --chown=gradle:gradle local.properties* .
RUN gradle dependencies --no-daemon || return 0
COPY --chown=gradle:gradle . .
RUN gradle build --no-daemon


FROM eclipse-temurin:24-jre-alpine
WORKDIR /app
COPY --from=build /home/gradle/project/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]