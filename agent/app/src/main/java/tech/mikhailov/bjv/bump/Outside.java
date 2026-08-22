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
 * <p>The answers are narrower than the truth where narrowing helps, and never narrower than
 * the truth about what is obtainable. That distinction was the difference between a typo and
 * an infra verdict: this file used to say the cache was the whole of what a build could get,
 * and a doer whose url had 404'd read the absence of its version as the environment's fault.
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

    /** Where every Gradle distribution comes from, named once so no caller rebuilds it. */
    private static final String BASE = "https://services.gradle.org/distributions/";

    /**
     * Which Gradle distributions this host already has, and the url for each.
     *
     * <p>A troubleshooter raised a wrapper from 8.15 to 8.16. Neither exists: Gradle went 8.14.3 to
     * 9.0, and it was incrementing a number and hoping. The build then spent its patience trying to
     * download a zip that has never been published. That is the gap this closes, the same one
     * inspect_jar closes for Maven coordinates: no way to ask what is real.
     *
     * <p>IT ANSWERS WITH URLS AND NOT WITH VERSIONS. Handing back "9.1.0" leaves the agent to
     * rebuild {@code .../distributions/gradle-9.1.0-bin.zip}, and the cache carries three
     * directories named {@code 8.5-bin}, {@code 9.0.0-bin} and {@code 9.1.0-bin}, which is the
     * shape a request for {@code .../distributions/9.1.0-bin.zip} leaves behind. The prefix was
     * dropped three times before anyone noticed, because nothing here ever showed the url.
     *
     * <p>AND IT NO LONGER CLAIMS A SEAL, because there is not one. This said the cached
     * distributions "are not merely the ones that exist, they are the only ones obtainable" and
     * that anything absent "cannot be downloaded and will fail the build". The build network
     * reaches services.gradle.org in a fifth of a second, the cache is mounted writable and
     * symlinked into the wrapper's own dists directory, and fourteen distributions arrived on their
     * own in a single day. A bump died of that sentence: the doer's url 404'd, it read the absence
     * of 9.1.0 from this list as proof the environment could not serve it, argued the point against
     * a reviewer, and the lane settled infra over a missing prefix.
     */
    static Map<ToolSpecification, ToolExecutor> gradle() {
        ToolSpecification spec = ToolSpecification.builder()
                .name("gradle_versions")
                .description("Gradle distributions already cached on this host, each with the "
                        + "exact distributionUrl. Paste the url rather than rebuilding it from the "
                        + "version, because rebuilding it is where the gradle- prefix gets dropped. "
                        + "A version that is not listed is NOT unavailable: the cache fills on "
                        + "demand and the build downloads what it needs. Gradle's version numbers "
                        + "are not contiguous, so do not assume the next one up exists. Urls shown "
                        + "as having failed to download did not resolve, which almost always means "
                        + "the url was typed rather than copied.")
                .parameters(JsonObjectSchema.builder().build())
                .build();
        ToolExecutor exec = (request, memoryId) -> {
            String dists = Env.get("BJV_GRADLE_DISTS");
            if (dists == null) {
                return "BJV_GRADLE_DISTS is unset, so nothing is cached here. That is not a "
                        + "blocker: the build downloads whatever distributionUrl names.";
            }
            return distributions(Path.of(dists));
        };
        Map<ToolSpecification, ToolExecutor> one = new LinkedHashMap<>();
        one.put(spec, exec);
        return one;
    }

    /** The cache's account of itself. Separate from the tool so a test can build one. */
    static String distributions(Path root) {
        if (!Files.isDirectory(root)) {
            return "the distribution cache is not readable from here";
        }
        List<String> usable = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        try (var entries = Files.list(root)) {
            for (Path dir : entries.filter(Files::isDirectory).toList()) {
                String name = dir.getFileName().toString();
                boolean complete;
                try (var deep = Files.walk(dir, 3)) {
                    complete = deep.anyMatch(f -> f.getFileName().toString().endsWith(".ok"));
                } catch (IOException unreadable) {
                    complete = false;
                }
                if (complete && name.startsWith("gradle-")) {
                    usable.add(name);
                } else if (!complete) {
                    // GRADLE NAMES THIS DIRECTORY AFTER THE URL'S FILENAME, so an incomplete one is
                    // a record of the exact request that failed, and the only record there is. The
                    // previous version dropped it, which left a diagnosing agent looking at an
                    // absence and free to invent a reason for it.
                    failed.add(BASE + name + ".zip");
                }
            }
        } catch (IOException unreadable) {
            return "could not read the distribution cache: " + unreadable.getMessage();
        }
        usable.sort((a, b) -> Migrate.compare(version(a), version(b)));
        StringBuilder out = new StringBuilder();
        if (usable.isEmpty()) {
            out.append("Nothing is cached here yet.\n");
        } else {
            out.append("Gradle distributions already cached here, oldest first. "
                    + "Paste the url as distributionUrl:\n");
            for (String name : usable) {
                out.append("  ").append(version(name)).append("  ")
                        .append(BASE).append(name).append(".zip\n");
            }
        }
        if (!failed.isEmpty()) {
            failed.sort(String::compareTo);
            out.append("\nAsked for here and did not download:\n  ")
                    .append(String.join("\n  ", failed)).append('\n');
        }
        out.append("\nThe cache fills on demand, so a version missing above is not unavailable: "
                + "the build fetches it. A url that did not download is usually one that was "
                + "typed rather than copied from this list.\n");
        return out.toString();
    }

    /** The version a distribution directory names, with the prefix and the -bin/-all taken off. */
    private static String version(String dir) {
        String name = dir.startsWith("gradle-") ? dir.substring("gradle-".length()) : dir;
        return name.replaceAll("-(bin|all)$", "");
    }
}
