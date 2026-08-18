package tech.mikhailov.bjv.agent;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;

/**
 * WHAT EACH AGENT CAN REACH, AND NOTHING MORE.
 *
 * <p>Producers get {@code edit_file} and never {@code write_file}: a new file is not a migration
 * step, it is something a critic would then have to catch in prose. Judges read. Everyone gets grep
 * and glob, because a model asking for a tool that does not exist does not degrade, it throws.
 *
 * <p>TEST CODE IS FENCED AT THE EXECUTOR. The prompts say "never edit a test" and a prompt is a
 * suggestion; this wrapper is the rule. An {@code edit_file} whose path sits under a test source
 * root is refused with an answer the model can read, whatever its reasoning said — because a lost
 * test scores zero regardless of anything else the bump achieves.
 *
 * <p>EVERY EXECUTOR IS WRAPPED SO THE TRACE SEES IT WHOLE. The library's flow listener truncates
 * its payloads, which is fine for watching and useless for the corpus: the argument to
 * {@code edit_file} IS the migration step. Recording at the executor catches it before anything
 * shortens it.
 *
 * <p>THE DEFINITIONS THEMSELVES LIVE NEXT DOOR, one file per kind of question a tool answers:
 * {@link Looking} reads a workspace, {@link Rewrites} moves a version in one, {@link Gauges} says
 * what the build files declare and what the compiler makes of them, {@link Outside} answers what
 * has been resolved and staged around the project, and {@link Ledger} answers what this run has
 * already done. What is left here is which of them each phase holds, and the two wrappers every
 * set passes through on its way out.
 */
final class Tools {

    private Tools() {
    }

    /** Read and look around: what a judge needs to check a claim. */
    static Map<ToolSpecification, ToolExecutor> reading(Path root, Tree tree, Trace trace,
                                                       String agent) {
        Map<ToolSpecification, ToolExecutor> tools =
                Looking.only(root, Set.of("list_dir", "read_file"));
        tools.putAll(Outside.jar());
        tools.putAll(Outside.gradle());
        tools.putAll(Ledger.history(tree, trace));
        return recorded(tools, trace, agent);
    }

    /** Read, look around, and move the workspace between landed steps. The outer troubleshoot pair. */
    static Map<ToolSpecification, ToolExecutor> steering(Path root, Tree tree, String floor,
                                                         Trace trace, String agent) {
        Map<ToolSpecification, ToolExecutor> tools =
                Looking.only(root, Set.of("list_dir", "read_file"));
        tools.putAll(Outside.jar());
        tools.putAll(Outside.gradle());
        tools.putAll(Ledger.history(tree, trace));
        tools.putAll(Ledger.rewind(tree, floor));
        return recorded(tools, trace, agent);
    }

    /** Read, look around, and edit EXISTING files — outside the test tree. Producers only. */
    static Map<ToolSpecification, ToolExecutor> patching(Path root, Runner runner, Tree tree,
                                                         String targetJdk, Trace trace,
                                                         String agent) {
        Map<ToolSpecification, ToolExecutor> tools =
                Looking.only(root, Set.of("list_dir", "read_file", "edit_file"));
        tools.putAll(Gauges.tryBuild(root, runner, targetJdk));
        tools.putAll(Outside.jar());
        tools.putAll(Outside.gradle());
        tools.putAll(Gauges.buildSystem(root));
        tools.putAll(Ledger.history(tree, trace));
        return recorded(guarded(tools), trace, agent);
    }

    /** The bumper: it edits build files by hand, and can see what it has left to do. */
    static Map<ToolSpecification, ToolExecutor> raising(Path root, Runner runner, Migrate migrate,
                                                        Tree tree, String targetJdk, String under,
                                                        Trace trace, String agent) {
        Map<ToolSpecification, ToolExecutor> tools =
                Looking.only(root, Set.of("list_dir", "read_file", "edit_file"));
        tools.putAll(Gauges.tryBuild(root, runner, targetJdk));
        tools.putAll(Gauges.targets(root, targetJdk));
        tools.putAll(Rewrites.patchBump(root, migrate, tree, under));
        tools.putAll(Rewrites.lineBump(migrate, tree, under));
        tools.putAll(Rewrites.recipe(migrate, tree, under));
        tools.putAll(Gauges.buildSystem(root));
        tools.putAll(Ledger.history(tree, trace));
        return recorded(guarded(tools), trace, agent);
    }

    /** Its critic: the same fact, and no way to edit. */
    static Map<ToolSpecification, ToolExecutor> checking(Path root, Tree tree, String targetJdk,
                                                          Trace trace, String agent) {
        Map<ToolSpecification, ToolExecutor> tools =
                Looking.only(root, Set.of("list_dir", "read_file"));
        tools.putAll(Gauges.targets(root, targetJdk));
        tools.putAll(Ledger.history(tree, trace));
        return recorded(tools, trace, agent);
    }

    /** A pin-phase producer: it may run recipes and check what landed, and edit nothing by hand. */
    static Map<ToolSpecification, ToolExecutor> pinning(Path root, Migrate migrate, Tree tree,
                                                        String jdk,
                                                        Trace trace, String agent) {
        Map<ToolSpecification, ToolExecutor> tools =
                Looking.only(root, Set.of("list_dir", "read_file"));
        // THE NAMED MOVE FIRST, the raw document last. A phase that owes a floor is nearly always
        // making one of two moves, and both of them are now something it can ask for by name.
        tools.putAll(Rewrites.patchBump(root, migrate, tree, jdk));
        tools.putAll(Rewrites.lineBump(migrate, tree, jdk));
        tools.putAll(Rewrites.recipe(migrate, tree, jdk));
        tools.putAll(Gauges.buildSystem(root));
        tools.putAll(Gauges.declaredVersions(root));
        tools.putAll(Outside.jar());
        tools.putAll(Outside.gradle());
        tools.putAll(Ledger.history(tree, trace));
        return recorded(tools, trace, agent);
    }

    /** A pin-phase critic: it reads and checks, and cannot run a recipe of its own. */
    static Map<ToolSpecification, ToolExecutor> judging(Path root, Tree tree,
                                                        Trace trace,
                                                        String agent) {
        Map<ToolSpecification, ToolExecutor> tools =
                Looking.only(root, Set.of("list_dir", "read_file"));
        tools.putAll(Gauges.declaredVersions(root));
        tools.putAll(Outside.jar());
        // A JUDGE THAT CANNOT CHECK A CLAIM CAN ONLY TRUST IT OR IGNORE IT, and this one
        // ignored it: a doer reported a pin unreachable because its module is Gradle and
        // apply_recipe runs the Maven plugin, and the verifier, seeing only that the version
        // was below the floor, answered `again` twice to a colleague with no tool to try
        // anything with. Same reason inspect_jar is given to judges.
        //
        // The premise is gone now -- there is a Gradle actuator -- but the tool stays. A verifier
        // that can see which build system a module is on can tell a pin that was not attempted
        // from one that was attempted and did not land, and those want different answers.
        tools.putAll(Gauges.buildSystem(root));
        tools.putAll(Ledger.history(tree, trace));
        return recorded(tools, trace, agent);
    }

    /** The one boundary that is enforced rather than requested. */
    private static boolean forbidden(String path) {
        return path.contains("src/test/") || path.contains("src/it/")
                || path.contains("src/integrationTest/") || path.contains("..");
    }

    private static Map<ToolSpecification, ToolExecutor> guarded(
            Map<ToolSpecification, ToolExecutor> tools) {
        Map<ToolSpecification, ToolExecutor> wrapped = new LinkedHashMap<>();
        tools.forEach((spec, executor) -> wrapped.put(spec, (request, memoryId) -> {
            if ("edit_file".equals(spec.name())) {
                String path = Reasoning.field(request.arguments(), "path");
                if (forbidden(path)) {
                    return "REFUSED: " + path + " is test code, which may never be edited. A lost "
                            + "test scores zero regardless of anything else this bump achieves.";
                }
            }
            return executor.execute(request, memoryId);
        }));
        return wrapped;
    }

    /**
     * What one tool call may add to the conversation.
     *
     * <p>The context grows MONOTONICALLY across an agent's tool calls and every call re-prefills all
     * of it, so an unbounded read is not paid once, it is paid again on every later call. A
     * generated pom or a vendored file is exactly the read that does this. The trace still records
     * the whole result; only what travels back into the prompt is bounded.
     */
    private static final int MAX_TOOL_RESULT = 20_000;

    private static Map<ToolSpecification, ToolExecutor> recorded(
            Map<ToolSpecification, ToolExecutor> tools, Trace trace, String agent) {
        Map<ToolSpecification, ToolExecutor> wrapped = new LinkedHashMap<>();
        tools.forEach((spec, executor) -> wrapped.put(spec, (request, memoryId) -> {
            try {
                String result = executor.execute(request, memoryId);
                // Recorded whole, returned bounded: the corpus wants everything, the prompt does not.
                trace.tool(agent, spec.name(), request.arguments(), result);
                if (result != null && result.length() > MAX_TOOL_RESULT) {
                    return result.substring(0, MAX_TOOL_RESULT) + "\n[truncated: " + result.length()
                            + " chars total. Narrow the request if you need the rest.]";
                }
                return result;
            } catch (RuntimeException e) {
                trace.tool(agent, spec.name(), request.arguments(),
                        "threw " + e.getClass().getSimpleName() + ": " + e.getMessage());
                throw e;
            }
        }));
        return wrapped;
    }
}
