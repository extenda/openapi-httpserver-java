FROM eclipse-temurin:21-jre-alpine

ENV BASEDIR=target
ENV JAR_FILE=http-server-openapi-*.jar
ENV JAR_NAME=http-server-openapi.jar
ENV CLASSPATH=./$JAR_NAME:./lib/*

WORKDIR /app

COPY ${BASEDIR}/lib/ lib/
COPY ${BASEDIR}/$JAR_FILE ./$JAR_NAME

ENTRYPOINT ["java", "-jar", "http-server-openapi.jar"]
