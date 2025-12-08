# Build stage
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /app

# Copy pom.xml și scarica dependentele
COPY pom.xml .
RUN mvn dependency:go-offline

# Copy sursa si build
COPY src/ ./src/
RUN mvn clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Copy jar-ul din builder stage
COPY --from=builder /app/target/*.jar app.jar

# Expose port (schimba 8080 daca aplicatia ta foloseste alt port)
EXPOSE 8080

# Environment variables (optional)
ENV JAVA_OPTS="-Xmx512m -Xms256m"

# Run aplicatia
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
