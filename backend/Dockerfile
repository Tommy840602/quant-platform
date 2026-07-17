# 通用服務映像：以已建置的 fat jar 執行。用 --build-arg JAR=<module>/target/xxx.jar 指定。
FROM eclipse-temurin:17-jre
WORKDIR /app
ARG JAR
COPY ${JAR} app.jar
ENV JAVA_OPTS="-Xms96m -Xmx384m -XX:+UseSerialGC"
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app.jar"]
