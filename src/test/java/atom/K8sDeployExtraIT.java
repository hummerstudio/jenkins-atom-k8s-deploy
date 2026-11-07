package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The rest of the k8s-deploy surface: the actions the first suite does not drive (delete, rollout), the kubectl
 * flags a job passes, the input checks each action makes, and the three failure classes — a rejected manifest, an
 * unreachable cluster, and a rollout that fails for a reason other than a timeout.
 */
class K8sDeployExtraIT {

    /** Records argv, answers each subcommand, and misbehaves only when the case asks it to. */
    private static final String FAKE_KUBECTL = """
            #!/bin/sh
            echo "$*" >> argv.txt
            if [ -n "${SMOKE_VERSIONLESS:-}" ] && [ "$1" = "version" ]; then exit 0; fi
            case "$*" in
              *"version --client"*) echo "Client Version: v1.31.0"; exit 0 ;;
              *"rollout status"*)
                if [ -n "${SMOKE_ROLLOUT_FAILED:-}" ]; then
                  echo 'error: deployment "app" failed to progress'
                  exit 1
                fi
                echo 'deployment "app" successfully rolled out'; exit 0 ;;
            esac
            if [ -n "${SMOKE_INVALID:-}" ]; then
              echo "error validating deployment: invalid image reference"
              exit 1
            fi
            if [ -n "${SMOKE_BROKEN:-}" ]; then
              echo "something odd happened"
              exit 1
            fi
            if [ -n "${SMOKE_HUGE:-}" ]; then
              i=0
              while [ $i -lt 1200 ]; do
                echo "0123456789012345678901234567890123456789012345678901234567890123456789012345678901234567890123456789"
                i=$((i+1))
              done
            fi
            echo "deployment.apps/app configured"
            echo "warning: token abc123-secret redacted by kubectl"
            exit 0
            """;

    /** Each case gets its own launcher: Atom accumulates inputs and environment, so reusing one leaks a prior case. */
    private Atom fresh() throws Exception {
        return Atom.of();
    }

    @Test
    @DisplayName("delete passes --ignore-not-found, the context, the namespace and the extra arguments")
    void deletesWithContextAndExtraArguments() throws Exception {
        AtomResult run = fresh().bin("kubectl", FAKE_KUBECTL)
                .input("action", "delete")
                .input("manifest", "k8s.yaml")
                .input("kubeContext", "staging")
                .input("namespace", "team-a")
                .input("extraArgs", "--dry-run=client   --recursive")
                .run();

        run.assertOk();
        String argv = run.file("argv.txt");
        assertTrue(argv.contains("delete --ignore-not-found -f k8s.yaml --dry-run=client --recursive"),
                "delete argv:\\n" + argv);
        assertTrue(argv.contains("--context staging"), "kubeContext not passed:\\n" + argv);
        assertTrue(argv.contains("--namespace team-a"), "namespace not passed:\\n" + argv);
        assertEquals("false", run.field("rolledOut"), "delete must not wait for a rollout:\\n" + argv);
    }

    @Test
    @DisplayName("the rollout action waits even when waitForRollout=false, because waiting is the action")
    void rolloutActionAlwaysWaits() throws Exception {
        AtomResult run = fresh().bin("kubectl", FAKE_KUBECTL)
                .input("action", "rollout")
                .input("deployment", "app")
                .input("waitForRollout", "false")
                .run();

        run.assertOk();
        String argv = run.file("argv.txt");
        long waits = argv.lines().filter(line -> line.contains("rollout status deployment/app")).count();
        assertTrue(waits >= 2, "expected the action and the wait, got:\\n" + argv);
        assertTrue(argv.contains("--timeout=300s"), "the wait carries the timeout:\\n" + argv);
        assertEquals("rollout", run.field("action"));
        assertEquals("true", run.field("rolledOut"));
    }

    @Test
    @DisplayName("each action refuses its own missing argument, with that action's code")
    void refusesMissingArgumentsPerAction() throws Exception {
        AtomResult setImage = fresh().bin("kubectl", FAKE_KUBECTL)
                .input("action", "setImage")
                .input("deployment", "app")
                .run();
        assertEquals("40003", setImage.field("errorCode"), "set image without an image:\\n" + setImage);
        assertEquals("1", setImage.field("errorType"));

        AtomResult delete = fresh().bin("kubectl", FAKE_KUBECTL)
                .input("action", "delete")
                .run();
        assertNotEquals(0, delete.exit(), "delete without a manifest must be refused");
        assertEquals("40005", delete.field("errorCode"), delete.toString());
        assertTrue(delete.field("message").contains("delete needs a manifest path"), delete.field("message"));

        AtomResult rollout = fresh().bin("kubectl", FAKE_KUBECTL)
                .input("action", "rollout")
                .run();
        assertEquals("40006", rollout.field("errorCode"), rollout.toString());
        assertEquals("1", rollout.field("errorType"), "a missing argument is the caller's problem");
        assertFalse(rollout.file("argv.txt").contains("rollout status"),
                "the refused rollout must not reach kubectl:\\n" + rollout.file("argv.txt"));
    }

    @Test
    @DisplayName("a kubeconfig that is neither a workspace file nor a document is refused before kubectl runs")
    void refusesAnUnusableKubeconfig() throws Exception {
        AtomResult run = fresh().bin("kubectl", FAKE_KUBECTL)
                .input("action", "apply")
                .input("manifest", "k8s.yaml")
                .input("kubeconfig", "../elsewhere/config")
                .run();

        assertEquals("40002", run.field("errorCode"), "kubeconfig outside the workspace:\\n" + run);
        assertEquals("1", run.field("errorType"));
        assertTrue(run.field("message").contains("neither a file in the workspace"),
                "message:\\n" + run.field("message"));
        assertFalse(run.exists("argv.txt"), "kubectl must not have been called");
    }

    @Test
    @DisplayName("a rejected manifest is USER, an unexplained failure is THIRD_PART")
    void classifiesManifestFailures() throws Exception {
        AtomResult invalid = fresh().bin("kubectl", FAKE_KUBECTL)
                .env("SMOKE_INVALID", "1")
                .input("action", "setImage")
                .input("deployment", "app")
                .input("image", "registry/app:1")
                .input("waitForRollout", "false")
                .run();
        assertEquals("1", invalid.field("errorType"), "an invalid manifest is USER:\\n" + invalid);
        assertEquals("40008", invalid.field("errorCode"));
        assertTrue(invalid.field("message").contains("setImage failed"),
                "message should name the action:\\n" + invalid.field("message"));

        AtomResult broken = fresh().bin("kubectl", FAKE_KUBECTL)
                .env("SMOKE_BROKEN", "1")
                .input("action", "setImage")
                .input("deployment", "app")
                .input("image", "registry/app:1")
                .input("waitForRollout", "false")
                .run();
        assertEquals("2", broken.field("errorType"), "an unexplained failure is THIRD_PART:\\n" + broken);
    }

    @Test
    @DisplayName("a rollout that fails without a timeout is THIRD_PART with 40010 and says which deployment")
    void reportsARolloutFailureThatIsNotATimeout() throws Exception {
        AtomResult run = fresh().bin("kubectl", FAKE_KUBECTL)
                .env("SMOKE_ROLLOUT_FAILED", "1")
                .input("action", "setImage")
                .input("deployment", "app")
                .input("image", "registry/app:2")
                .run();

        assertEquals("40010", run.field("errorCode"), run.toString());
        assertEquals("2", run.field("errorType"), "a failed rollout is not the caller's typo");
        assertTrue(run.field("message").contains("the rollout of app failed"),
                "message:\\n" + run.field("message"));
        assertTrue(run.field("message").contains("failed to progress"),
                "the tail of kubectl's answer should be kept:\\n" + run.field("message"));
    }

    @Test
    @DisplayName("the client version is logged, and a line that mentions a token never reaches the log")
    void logsTheVersionAndRedactsTokens() throws Exception {
        AtomResult run = fresh().bin("kubectl", FAKE_KUBECTL)
                .input("action", "setImage")
                .input("deployment", "app")
                .input("image", "registry/app:3")
                .input("waitForRollout", "false")
                .run();

        run.assertOk();
        assertTrue(run.console().contains("Client Version: v1.31.0"),
                "the version line was not logged:\\n" + run.console());
        assertFalse(run.console().contains("abc123-secret"),
                "a token-bearing line was echoed to the log:\\n" + run.console());

        AtomResult silent = fresh().bin("kubectl", FAKE_KUBECTL)
                .env("SMOKE_VERSIONLESS", "1")
                .input("action", "setImage")
                .input("deployment", "app")
                .input("image", "registry/app:3")
                .input("waitForRollout", "false")
                .run();
        silent.assertOk();
        assertTrue(silent.console().contains("[k8s] kubectl"),
                "an empty version should be logged as 'kubectl':\\n" + silent.console());
    }

    @Test
    @DisplayName("a kubectl path that is not executable is an environment error")
    void refusesANonExecutableBinary() throws Exception {
        AtomResult run = fresh().input("kubectlBinary", "tools/not-a-kubectl").run();

        assertEquals("5", run.field("errorType"), run.toString());
        assertEquals("40001", run.field("errorCode"));
        assertTrue(run.field("message").contains("not on the PATH"), run.field("message"));
    }

    @Test
    @DisplayName("more than 100k of kubectl output is kept small instead of filling the log")
    void truncatesHugeOutput() throws Exception {
        AtomResult run = fresh().bin("kubectl", FAKE_KUBECTL)
                .env("SMOKE_HUGE", "1")
                .input("action", "setImage")
                .input("deployment", "app")
                .input("image", "registry/app:4")
                .input("waitForRollout", "false")
                .run();

        run.assertOk();
        assertTrue(run.console().length() < 100_000,
                "the console kept " + run.console().length() + " characters of kubectl output");
        assertEquals("true", run.field("applied"));
    }
}
