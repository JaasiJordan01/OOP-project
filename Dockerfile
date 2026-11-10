FROM eclipse-temurin:17-jdk-alpine
WORKDIR /app
COPY YEPWebApp.java .
EXPOSE 8080
CMD ["java", "YEPWebApp.java"]