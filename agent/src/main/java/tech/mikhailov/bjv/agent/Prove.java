package tech.mikhailov.bjv.agent;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.ToolExecutor;

import java.nio.file.Path;
import java.util.Map;

/**
 * Drive one pin tool against one real repository, through the tool surface an agent sees.
 *
 * <p>Not a unit test and not a shell equivalent of one: this builds the same tool map
 * {@code before-pins-doer} is handed, looks the tool up by the name the model would call, and hands
 * it the JSON a model would send. What it proves is the whole path, from the tool call through the
 * generated document and the chosen actuator to the file on disk.
 *
 * <p>{@code Prove <ws> <hoptools> <jdk> <tool> <groupId> <artifactId> <newVersion>}
 */
final class Prove {

    public static void main(String[] args) throws Exception {
        Path ws = Path.of(args[0]);
        String hoptools = args[1];
        String jdk = args[2];
        String tool = args[3];
        String arguments = String.format(
                "{\"groupId\":\"%s\",\"artifactId\":\"%s\",\"newVersion\":\"%s\"}",
                args[4], args[5], args[6]);

        // Every abstract method, doing nothing except where a proof wants to see it.
        Trace trace = new Trace() {
            @Override
            public void asked(String agent, String prompt, String reply) {
            }

            @Override
            public void applied(String stage, String what) {
                System.out.println("[applied " + stage + "] " + what);
            }

            @Override
            public void tool(String agent, String name, String arguments, String result) {
                System.out.println("[tool " + name + "] " + arguments);
            }

            @Override
            public void thought(String finishReason, String thinking, String content) {
            }

            @Override
            public void built(String phase, Runner.Result result) {
            }

            @Override
            public void settled(String bump, String state, String because, boolean baselineGreen,
                                boolean gateGreen) {
            }

            @Override
            public void failed(String bump, Throwable cause) {
                System.out.println("[failed] " + cause);
            }

            @Override
            public void progress(String bump, String note) {
                System.out.println("[progress] " + note);
            }

            @Override
            public void priced(String bump, String minutes, String itemisation) {
            }
        };

        // What Bump does before any agent runs, so the harness's own files do not read back as
        // the agent's work when Rewrites.reported() asks whether the tree moved.
        Tree tree = new Tree(ws, null);
        tree.excludeBuildOutput();
        System.out.println("actuator this workspace needs: " + Migrate.actuatorFor(ws));
        Map<ToolSpecification, ToolExecutor> tools = Tools.pinning(
                ws, new Migrate(ws, hoptools, trace), tree, jdk, trace,
                "before-pins-doer");
        System.out.println("tools offered: " + tools.keySet().stream()
                .map(ToolSpecification::name).toList());

        ToolExecutor exec = tools.entrySet().stream()
                .filter(e -> e.getKey().name().equals(tool))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("no tool " + tool))
                .getValue();

        String said = exec.execute(
                ToolExecutionRequest.builder().name(tool).arguments(arguments).build(), null);
        System.out.println("---- what the tool answered ----");
        System.out.println(said.length() > 4000 ? said.substring(0, 4000) + "\n[...]" : said);
    }

    private Prove() {
    }
}
