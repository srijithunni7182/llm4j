# Fabric, the server many Loom processes report to.
#
#   mvn -pl src/loom/loom-fabric -am package -DskipTests
#   docker build -f src/loom/loom-fabric/Dockerfile -t loom-fabric src/loom/loom-fabric
#
# First run, once (creates /data/vault.json and /data/master.key, asks nothing when the password is in the environment):
#   docker run --rm -v fabric-data:/data -e LOOM_FABRIC_ADMIN_PASSWORD='a long password' loom-fabric init --data /data
#   docker run --rm -v fabric-data:/data loom-fabric clients add getviral-prod --data /data
#
# Then serve. Put TLS in front (a reverse proxy, passed --trust-proxy) or give it a keystore (--tls-keystore):
#   docker run -d --name fabric -v fabric-data:/data -p 9090:9090 loom-fabric serve --data /data --bind 0.0.0.0 --trust-proxy
#
# Keep master.key out of the data volume's backups: without it the vault cannot be read.
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --home-dir /data fabric && mkdir -p /data && chown fabric /data
COPY target/loom-fabric-*-all.jar /opt/fabric/fabric.jar
USER fabric
VOLUME /data
EXPOSE 9090
ENTRYPOINT ["java", "-jar", "/opt/fabric/fabric.jar"]
CMD ["serve", "--data", "/data", "--bind", "0.0.0.0", "--trust-proxy"]
