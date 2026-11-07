package com.jenkinschina.atom.k8sdeploy;

import com.jenkinschina.atom.sdk.AtomContext;
import com.jenkinschina.atom.sdk.AtomResult;
import com.jenkinschina.atom.sdk.AtomTask;
import com.jenkinschina.atom.sdk.ErrorType;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Deploy to Kubernetes with {@code kubectl}.
 *
 * <p>Covers the step-shaped half of the kubernetes plugins: apply a manifest, or set the image of one deployment
 * and wait for the rollout. A Deployment that never becomes ready is the failure people actually hit, so
 * {@code waitForRollout} is on by default and a timeout is reported as a failed step, not a green one.
 */
public class K8sDeployAtom implements AtomTask {

    @Override
    public AtomResult run(AtomContext context) throws Exception {
        Path workspace = context.getWorkspace();
        String kubectl = resolveBinary(context.getParam("kubectlBinary", "kubectl"));
        String kubeconfig = context.getParam("kubeconfig", "");
        String contextName = context.getParam("kubeContext", "");
        String namespace = context.getParam("namespace", "");
        String action = context.getParam("action", "setImage").trim();
        String deployment = context.getParam("deployment", "").trim();
        String container = context.getParam("container", "").trim();
        String image = context.getParam("image", "").trim();
        String manifest = context.getParam("manifest", "").trim();
        String extraArgs = context.getParam("extraArgs", "");
        boolean waitForRollout = context.getBooleanParam("waitForRollout", true);
        int timeoutSeconds = Math.max(30, Math.min(context.getIntParam("timeoutSeconds", 300), 3600));

        if (kubectl == null) {
            return AtomResult.error(ErrorType.ENVIRONMENT, 40001,
                    "the kubectl CLI is not on the PATH of this agent");
        }

        Path kubeconfigFile = null;
        try {
            List<String> base = new ArrayList<>();
            base.add(kubectl);
            if (!kubeconfig.isBlank()) {
                // either a path in the workspace or the file content itself, written to a 0600 file
                Path candidate = workspace.resolve(kubeconfig).normalize();
                if (candidate.startsWith(workspace) && Files.isRegularFile(candidate)) {
                    kubeconfigFile = candidate;
                } else if (kubeconfig.contains("apiVersion")) {
                    kubeconfigFile = writeSecret(workspace, ".atoms/kubeconfig", kubeconfig);
                } else {
                    return AtomResult.error(ErrorType.USER, 40002,
                            "kubeconfig is neither a file in the workspace nor a kubeconfig document");
                }
                base.add("--kubeconfig");
                base.add(kubeconfigFile.toString());
            }
            if (!contextName.isBlank()) {
                base.add("--context");
                base.add(contextName);
            }
            if (!namespace.isBlank()) {
                base.add("--namespace");
                base.add(namespace);
            }

            String version = firstLine(exec(workspace, context, base, "version", "--client", "--short").output());
            context.log("[k8s] " + (version.isEmpty() ? "kubectl" : version));

            List<String> command = new ArrayList<>(base);
            switch (action) {
                case "setImage" -> {
                    if (deployment.isEmpty() || image.isEmpty()) {
                        return AtomResult.error(ErrorType.USER, 40003,
                                "setImage needs deployment and image");
                    }
                    command.add("set");
                    command.add("image");
                    command.add("deployment/" + deployment);
                    command.add(container.isEmpty() ? "*=" + image : container + "=" + image);
                }
                case "apply" -> {
                    if (manifest.isEmpty()) {
                        return AtomResult.error(ErrorType.USER, 40004, "apply needs a manifest path");
                    }
                    command.add("apply");
                    command.add("-f");
                    command.add(manifest);
                }
                case "delete" -> {
                    if (manifest.isEmpty()) {
                        return AtomResult.error(ErrorType.USER, 40005, "delete needs a manifest path");
                    }
                    command.add("delete");
                    command.add("--ignore-not-found");
                    command.add("-f");
                    command.add(manifest);
                }
                case "rollout" -> {
                    if (deployment.isEmpty()) {
                        return AtomResult.error(ErrorType.USER, 40006, "rollout needs a deployment");
                    }
                    command.add("rollout");
                    command.add("status");
                    command.add("deployment/" + deployment);
                    waitForRollout = true;
                }
                default -> {
                    return AtomResult.error(ErrorType.USER, 40007,
                            "unknown action '" + action + "'; known: setImage, apply, delete, rollout");
                }
            }
            command.addAll(split(extraArgs));

            context.log("[k8s] " + join(command));
            Result applied = exec(workspace, context, command, null, timeoutSeconds);
            if (!applied.ok()) {
                return AtomResult.error(classify(applied), 40008,
                        "kubectl " + action + " failed (" + applied.code() + "): " + applied.tail());
            }

            boolean rolledOut = false;
            if (waitForRollout && !deployment.isEmpty()) {
                List<String> rollout = new ArrayList<>(base);
                rollout.add("rollout");
                rollout.add("status");
                rollout.add("deployment/" + deployment);
                rollout.add("--timeout=" + timeoutSeconds + "s");
                context.log("[k8s] " + join(rollout));
                Result status = exec(workspace, context, rollout, null, timeoutSeconds + 30);
                if (!status.ok()) {
                    boolean timeout = status.output().toLowerCase(java.util.Locale.ROOT).contains("timed out");
                    return AtomResult.error(timeout ? ErrorType.THIRD_PART : classify(status),
                            timeout ? 40009 : 40010,
                            timeout
                                    ? "the rollout of " + deployment + " did not finish within " + timeoutSeconds + "s"
                                    : "the rollout of " + deployment + " failed: " + status.tail());
                }
                rolledOut = true;
                context.log("[k8s] " + firstLine(status.output()));
            }

            return AtomResult.success()
                    .data("action", action)
                    .data("deployment", deployment)
                    .data("image", image)
                    .data("applied", "true")
                    .data("rolledOut", rolledOut ? "true" : "false");
        } finally {
            if (kubeconfigFile != null && kubeconfigFile.toString().contains(".atoms")) {
                Files.deleteIfExists(kubeconfigFile);
            }
        }
    }

    /** A cluster that is not reachable is the environment's problem; a rejected manifest is the caller's. */
    private static ErrorType classify(Result result) {
        String text = result.output().toLowerCase(java.util.Locale.ROOT);
        // kubectl words this several ways: "was refused", "no such host", "dial tcp ... i/o timeout"
        if (text.contains("refused") || text.contains("no such host") || text.contains("unable to connect")
                || text.contains("i/o timeout") || text.contains("dial tcp")) {
            return ErrorType.ENVIRONMENT;
        }
        if (text.contains("forbidden") || text.contains("unauthorized")
                || text.contains("error validating") || text.contains("invalid")) {
            return ErrorType.USER;
        }
        return ErrorType.THIRD_PART;
    }

    private static Path writeSecret(Path workspace, String relative, String content) throws Exception {
        Path file = workspace.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        try {
            Files.setPosixFilePermissions(file,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException ignored) {
            file.toFile().setReadable(false, false);
            file.toFile().setReadable(true, true);
        }
        return file;
    }

    private static String resolveBinary(String name) {
        if (name != null && (name.contains("/") || name.contains("\\"))) {
            Path path = Path.of(name);
            return Files.isExecutable(path) ? path.toAbsolutePath().toString() : null;
        }
        String env = System.getenv("PATH");
        if (env == null) {
            return null;
        }
        for (String entry : env.split(java.io.File.pathSeparator)) {
            if (entry.isBlank()) {
                continue;
            }
            Path candidate = Path.of(entry).resolve(name);
            if (Files.isExecutable(candidate)) {
                return candidate.toAbsolutePath().toString();
            }
        }
        return null;
    }

    private static List<String> split(String text) {
        List<String> parts = new ArrayList<>();
        for (String part : text.trim().split("\\s+")) {
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        return parts;
    }

    private static String join(List<String> command) {
        StringBuilder text = new StringBuilder();
        for (String part : command) {
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(part);
        }
        return text.toString();
    }

    private static Result exec(Path workspace, AtomContext context, List<String> base, String... trailing) {
        List<String> command = new ArrayList<>(base);
        command.addAll(List.of(trailing));
        return exec(workspace, context, command, null, 600);
    }

    private static Result exec(Path workspace, AtomContext context, List<String> command, String ignored,
                              int timeoutSeconds) {
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true);
            // a pipeline has nobody to answer a prompt: fail instead of waiting forever
            builder.environment().put("KUBECTL_TERMINAL_PROMPT", "0");
            Process process = builder.start();
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.toLowerCase(java.util.Locale.ROOT).contains("token")) {
                        continue; // never echo something that looks like a credential
                    }
                    output.append(line).append('\n');
                    if (output.length() > 100_000) {
                        output.delete(0, 50_000);
                    }
                }
            }
            if (!process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return new Result(-1, "timed out after " + timeoutSeconds + "s\n" + output);
            }
            return new Result(process.exitValue(), output.toString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(-1, String.valueOf(e));
        } catch (Exception e) {
            return new Result(-1, String.valueOf(e));
        }
    }

    private static String firstLine(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.strip().lines().findFirst().orElse("").trim();
    }

    private record Result(int code, String output) {
        boolean ok() {
            return code == 0;
        }

        String tail() {
            String[] lines = output.strip().split("\n");
            StringBuilder out = new StringBuilder();
            for (int i = Math.max(0, lines.length - 6); i < lines.length; i++) {
                out.append(lines[i]).append('\n');
            }
            return out.toString().strip();
        }
    }
}
