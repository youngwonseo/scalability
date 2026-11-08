FROM eclipse-temurin:25-jre AS extract
WORKDIR /build
COPY build/libs/sns.jar sns.jar
# Run from an extracted jar: the nested-jar class loader of an executable Spring Boot jar takes a lock
# per class load, and with virtual threads on 1-2 CPUs that deadlocked the app under load.
RUN java -Djarmode=tools -jar sns.jar extract --destination /app

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=extract /app /app
EXPOSE 8080
# Class loading pins virtual threads to their carrier; keep at least 4 carriers even on small instances.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Djdk.virtualThreadScheduler.parallelism=4", "-jar", "sns.jar"]
