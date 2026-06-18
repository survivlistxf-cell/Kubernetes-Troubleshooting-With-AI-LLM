# Build stage
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /app

# Set Maven memory options
ENV MAVEN_OPTS="-Xmx1536m -Xms768m"

# Copy pom.xml and download dependencies
COPY pom.xml .
RUN mvn dependency:go-offline

# Copy source and build
COPY src/ ./src/
RUN mvn clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Copy jar from builder stage
COPY --from=builder /app/target/*.jar app.jar

# Expose AI server port
EXPOSE 8090

# Environment variables (optional)
ENV JAVA_OPTS="-Xmx1536m -Xms768m -Djava.net.preferIPv4Stack=true"

# Run the application
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]