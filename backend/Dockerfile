# Builds the DeployGuard jar from source, then runs it on a small Java 21 runtime.
# The same image runs both roles; the command picks "controller" or "agent".
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
COPY src ./src
# The cache mount keeps downloaded Maven dependencies between builds.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/deployguard.jar /app/deployguard.jar
ENV JAVA_OPTS="-Xmx192m"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/deployguard.jar \"$@\"", "--"]
CMD ["controller"]
