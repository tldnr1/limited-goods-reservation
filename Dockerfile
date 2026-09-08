FROM eclipse-temurin:21-jre
WORKDIR /app
COPY build/libs/limited-goods.jar app.jar
ENTRYPOINT ["java","-XX:MaxRAMPercentage=65","-jar","app.jar"]
