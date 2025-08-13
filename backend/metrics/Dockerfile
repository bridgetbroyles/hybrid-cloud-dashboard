# Using docker for the backend causes the cpu values to be based on the isolated environement
# Do not use 

FROM openjdk:17-jdk-slim

WORKDIR /app

COPY . .

RUN chmod +x mvnw

RUN ./mvnw clean package -DskipTests

EXPOSE 6001

CMD ["java", "-jar", "target/metrics-0.0.1-SNAPSHOT.jar"]

