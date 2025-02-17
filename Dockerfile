FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

COPY target/lib/ lib/
COPY target/classes classes/
COPY target/test-classes test-classes/

ENTRYPOINT ["java", "-cp", "lib/*:classes:test-classes", "com.retailsvc.http.start.ServerLauncher"]
