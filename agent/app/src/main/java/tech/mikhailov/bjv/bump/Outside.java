package tech.mikhailov.bjv.bump;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.ToolExecutor;

import tech.mikhailov.ratchet.config.Env;
import tech.mikhailov.ratchet.record.Json;
import tech.mikhailov.bjv.jvm.Jars;
import tech.mikhailov.bjv.jvm.Migrate;

/**
 * THE QUESTIONS THE WORKSPACE CANNOT ANSWER, asked of what has been staged around it.
 *
 * <p>What is actually inside a resolved dependency, and which Gradle distributions this host can
 * hand a build. Neither fact is in the project's files, and an agent with no way to ask invents
 * one: a wrapper was raised to Gradle 8.16, a release that has never existed, and a Boot 2 to 3
 * claim about javax was argued from a pom that says nothing about either.
 *
 * <p>The answers are narrower than the truth, deliberately. The builds run sealed, so what is
 * staged here is not merely what exists, it is the whole of what is obtainable.
 */
final class Outside {

    private Outside() {
    }

    /**
     * Look inside a dependency, which is the one question the workspace cannot answer.
     *
     * <p>Given to producers and judges alike. A judge that cannot open the jar cannot check a claim
     * about what is in it, and the claims worth checking in a Boot 2 to 3 migration are all of that
     * shape.
     */
    static Map<ToolSpecification, ToolExecutor> jar() {
        ToolSpecification spec = ToolSpecification.builder()
                .name("inspect_jar")
                .description("Look inside a DEPENDENCY jar already resolved into the local Maven "
                        + "repository. Answers what the project's own files cannot: whether an "
                        + "artifact is compiled against javax or jakarta, how it registers with "
                        + "Spring (spring.factories is Boot 2 only and Boot 3 ignores it), which "
                        + "versions are present, what types it holds, and whether a given type is a "
                        + "class or an interface. Pass `type` to see one type's members.")
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("artifact",
                                "groupId:artifactId, optionally :version, e.g. "
                                        + "com.baomidou:kaptcha-spring-boot-starter:1.1.0")
                        .addStringProperty("type",
                                "optional fully-qualified type to describe, e.g. "
                                        + "com.baomidou.kaptcha.Kaptcha")
                        .required("artifact")
                        .build())
                .build();
        ToolExecutor exec = (request, memoryId) -> {
            String artifact = Json.read(request.arguments(), "artifact");
            String type = Json.read(request.arguments(), "type");
            String[] parts = artifact.split(":");
            if (parts.length < 2) {
                return "artifact must be groupId:artifactId or groupId:artifactId:version, got: "
                        + artifact;
            }
            String version = parts.length > 2 ? parts[2] : null;
            try {
                Jars jars = Jars.local();
                return type.isBlank() ? jars.describe(parts[0], parts[1], version)
                        : jars.describeType(parts[0], parts[1], version, type);
            } catch (Jars.NotFound absent) {
                return absent.getMessage();
            }
        };
        Map<ToolSpecification, ToolExecutor> one = new LinkedHashMap<>();
        one.put(spec, exec);
        return one;
    }

    /**
     * Which Gradle distributions can actually be used here.
     *
     * <p>A troubleshooter raised a wrapper from 8.15 to 8.16. Neither exists: Gradle went 8.14.3 to
     * 9.0, and it was incrementing a number and hoping. The build then spent its patience trying to
     * download a zip that has never been published. This is the same gap inspect_jar closed for
     * Maven coordinates -- no way to ask what is real -- and the answer is narrower here, because
     * the builds run sealed: the distributions staged on this host are not merely the ones that
     * exist, they are the only ones obtainable.
     */
    static Map<ToolSpecification, ToolExecutor> gradle() {
        ToolSpecification spec = ToolSpecification.builder()
                .name("gradle_versions")
                .description("The Gradle distributions available to builds here, oldest first. "
                        + "Check this before editing distributionUrl in gradle-wrapper.properties: "
                        + "the builds are sealed, so a version missing from this list cannot be "
                        + "downloaded and the build will fail trying. Gradle version numbers are "
                        + "not contiguous, so do not assume the next one up exists.")
                .parameters(JsonObjectSchema.builder().build())
                .build();
        ToolExecutor exec = (request, memoryId) -> {
            String dists = Env.get("BJV_GRADLE_DISTS");
            if (dists == null) {
                return "BJV_GRADLE_DISTS is unset; no sealed Gradle distributions are available";
            }
            Path root = Path.of(dists);
            if (!Files.isDirectory(root)) {
                return "the distribution cache is not readable from here";
            }
            List<String> usable = new ArrayList<>();
            try (var entries = Files.list(root)) {
                for (Path dir : entries.filter(Files::isDirectory).toList()) {
                    String name = dir.getFileName().toString();
                    if (!name.startsWith("gradle-")) {
                        continue;
                    }
                    // A half-downloaded distribution has no .ok marker and is not usable: offering
                    // it would send a build to the same download that failed to finish before.
                    boolean complete;
                    try (var deep = Files.walk(dir, 3)) {
                        complete = deep.anyMatch(f -> f.getFileName().toString().endsWith(".ok"));
                    } catch (IOException unreadable) {
                        complete = false;
                    }
                    if (complete) {
                        usable.add(name.substring("gradle-".length()));
                    }
                }
            } catch (IOException e) {
                return "could not read the distribution cache: " + e.getMessage();
            }
            if (usable.isEmpty()) {
                return "no complete Gradle distribution is staged here";
            }
            usable.sort((a, b) -> Migrate.compare(a.replaceAll("-(bin|all)$", ""),
                    b.replaceAll("-(bin|all)$", "")));
            return "Gradle distributions usable in a sealed build, oldest first:\n  "
                    + String.join("\n  ", usable)
                    + "\n\nAnything not on this list cannot be downloaded and will fail the build.";
        };
        Map<ToolSpecification, ToolExecutor> one = new LinkedHashMap<>();
        one.put(spec, exec);
        return one;
    }
}
