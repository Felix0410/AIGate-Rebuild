# 1、使用固定摘要的 JRE 镜像。
FROM eclipse-temurin:21-jre@sha256:227118764cccd3c3294617061aaa93a08309c63a395eefa83e6b35032992ea99

WORKDIR /app
COPY --chown=10001:10001 aigate-gateway/target/aigate-gateway-0.1.0-SNAPSHOT.jar app.jar

# 2、切换为非 root 用户运行，凭证通过环境变量注入。
USER 10001:10001
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
