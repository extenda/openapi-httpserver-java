FROM eclipse-temurin:21-jre-alpine

ENV BASEDIR=target
ENV JAR_FILE=openapi-httpserver-java-*.jar
ENV JAR_NAME=openapi-httpserver-java.jar
ENV CLASSPATH=./$JAR_NAME:./lib/*

WORKDIR /app

COPY ${BASEDIR}/lib/ lib/
COPY ${BASEDIR}/$JAR_FILE ./$JAR_NAME

ENTRYPOINT ["java", "-jar", "openapi-httpserver-java.jar"]
