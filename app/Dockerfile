FROM eclipse-temurin:25-jre
WORKDIR /app
COPY build/libs/sns.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
