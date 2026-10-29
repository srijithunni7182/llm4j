package io.github.llm4j.loom.deploy;

/**
 * @param target docker, compose or k8s
 * @param name the app and image name
 * @param baseImage the runtime image (a tag, or tag@digest)
 * @param withFabric include a Fabric server and wire the app to it
 * @param fabricImage the Fabric server image
 * @param journal volume, none, or jdbc:URL
 * @param expose none, or the port the app listens on
 * @param schedule true to run the daemon that keeps the script's schedules
 * @param fabricPublish the address Fabric's page is published on (compose)
 * @param reviewSummary the review's state when this was generated, for the README
 */
public record DeployOptions(
        String target,
        String name,
        String baseImage,
        boolean withFabric,
        String fabricImage,
        String journal,
        String expose,
        boolean schedule,
        String fabricPublish,
        String reviewSummary) {

    public static final String DEFAULT_BASE = "eclipse-temurin:17-jre";
    public static final String DEFAULT_FABRIC = "loom-fabric:6.1.0";
}
