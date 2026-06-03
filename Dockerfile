# Stage 1: Build using Java 21
FROM maven:3.9.6-eclipse-temurin-21 AS build
COPY . .
RUN mvn clean package -DskipTests

# Stage 2: Run using the exact same Java 21 version
FROM eclipse-temurin:21-jdk-jammy
COPY --from=build /org.example/MyFirstWebApp/target/MyFirstWebApp-1.0-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
