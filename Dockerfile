FROM amazoncorretto:23-alpine
EXPOSE 8080:8080

RUN mkdir /app
RUN mkdir /config
COPY build/libs/backend-all.jar /app/app.jar
CMD ["java","-jar","/app/app.jar"]
