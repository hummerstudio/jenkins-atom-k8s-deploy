package atom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jenkinschina.atom.sdk.testing.Atom;
import com.jenkinschina.atom.sdk.testing.AtomResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The k8s-deploy atom, started the way the runner starts it, with a stub {@code kubectl} on PATH: what can be checked
 * here is the command line it receives and how each outcome is judged.
 *
 * <p>These are the checks {@code tools/smoke.sh} made — setImage plus the rollout wait, an inline kubeconfig that is
 * written and removed, a rollout that times out, an unauthorized cluster versus an unreachable one, bad input and a
 * missing kubectl.
 */
class K8sDeployIT {

    /** Records its argv in {@code argv.txt} in the workspace, then answers the way the scenario asks. */
    private static final String FAKE_KUBECTL = """
            #!/bin/sh
            echo "$*" >> argv.txt
            if [ -n "${SMOKE_CONNREFUSED:-}" ]; then
              echo "The connection to the server 127.0.0.1:6443 was refused"
              exit 1
            fi
            case "$*" in
              *"version --client"*) echo "Client Version: v1.31.0"; exit 0 ;;
              *"rollout status"*)
                case "${SMOKE_ROLLOUT:-ok}" in
                  ok) echo 'deployment "app" successfully rolled out'; exit 0 ;;
                  timeout) echo 'error: deployment "app" exceeded its progress deadline'
                           echo "timed out waiting for the condition"; exit 1 ;;
                  failed) echo 'error: deployment "app" failed'; exit 1 ;;
                esac ;;
              *"set image"*)
                if [ -n "${SMOKE_UNAUTHORIZED:-}" ]; then
                  echo "error: You must be logged in to the server (Unauthorized)"
                  exit 1
                fi
                echo "deployment.apps/app image updated"; exit 0 ;;
            esac
            echo ok
            exit 0
            """;

    private Atom atom;

    @BeforeEach
    void setUp() throws Exception {
        atom = Atom.of();
    }

    @Test
    @DisplayName("setImage sets the image, passes the namespace and waits for the rollout")
    void setsTheImageAndWaitsForTheRollout() throws Exception {
        AtomResult run = atom.bin("kubectl", FAKE_KUBECTL)
                .file("kubeconfig", "apiVersion: v1\nkind: Config\n")
                .input("action", "setImage")
                .input("deployment", "app")
                .input("container", "api")
                .input("image", "registry/app:2.0.0")
                .input("namespace", "prod")
                .input("kubeconfig", "kubeconfig")
                .run();

        run.assertOk();
        String argv = run.file("argv.txt");
        assertTrue(argv.contains("set image deployment/app api=registry/app:2.0.0"),
                "set image argv:\n" + argv);
        assertTrue(argv.contains("--namespace prod"), "namespace not passed:\n" + argv);
        assertTrue(argv.contains("rollout status deployment/app --timeout=300s"),
                "rollout was not waited for:\n" + argv);
        assertEquals("true", run.field("rolledOut"), "rolledOut flag:\n" + run.toString());
    }

    @Test
    @DisplayName("an inline kubeconfig is written for the call and removed afterwards")
    void writesAndRemovesAnInlineKubeconfig() throws Exception {
        AtomResult run = atom.bin("kubectl", FAKE_KUBECTL)
                .input("action", "apply")
                .input("manifest", "k8s.yaml")
                .input("kubeconfig", "apiVersion: v1\nkind: Config\nclusters: []")
                .run();

        run.assertOk();
        String argv = run.file("argv.txt");
        assertTrue(argv.contains("--kubeconfig"), "inline kubeconfig not passed:\n" + argv);
        assertTrue(argv.contains("apply -f k8s.yaml"), "apply argv:\n" + argv);
        assertFalse(run.exists(".atoms/kubeconfig"), "the inline kubeconfig should be deleted after the run");
    }

    @Test
    @DisplayName("a rollout that never finishes fails the step, and says so")
    void reportsARolloutTimeout() throws Exception {
        AtomResult run = atom.bin("kubectl", FAKE_KUBECTL)
                .env("SMOKE_ROLLOUT", "timeout")
                .input("action", "setImage")
                .input("deployment", "app")
                .input("image", "registry/app:3.0.0")
                .run();

        assertNotEquals(0, run.exit(), "a timed-out rollout must fail");
        assertTrue(run.field("message").contains("did not finish within"),
                "timeout message:\n" + run.toString());
    }

    @Test
    @DisplayName("an unauthorized cluster is the caller's problem")
    void classifiesAnUnauthorizedClusterAsUserError() throws Exception {
        AtomResult run = atom.bin("kubectl", FAKE_KUBECTL)
                .env("SMOKE_UNAUTHORIZED", "1")
                .input("action", "setImage")
                .input("deployment", "app")
                .input("image", "x")
                .input("waitForRollout", "false")
                .run();

        assertNotEquals(0, run.exit(), "unauthorized must fail");
        assertEquals("1", run.field("errorType"), "unauthorized should be USER(1):\n" + run.toString());
    }

    @Test
    @DisplayName("an unreachable cluster is the environment's problem")
    void classifiesAnUnreachableClusterAsEnvironment() throws Exception {
        AtomResult run = atom.bin("kubectl", FAKE_KUBECTL)
                .env("SMOKE_CONNREFUSED", "1")
                .input("action", "setImage")
                .input("deployment", "app")
                .input("image", "x")
                .input("waitForRollout", "false")
                .run();

        assertNotEquals(0, run.exit(), "an unreachable cluster must fail");
        assertEquals("5", run.field("errorType"), "unreachable should be ENVIRONMENT(5):\n" + run.toString());
    }

    @Test
    @DisplayName("an unknown action is refused and lists the ones that exist")
    void refusesAnUnknownAction() throws Exception {
        AtomResult run = atom.bin("kubectl", FAKE_KUBECTL)
                .input("action", "explode")
                .run();

        assertNotEquals(0, run.exit(), "unknown action must be refused");
        assertTrue(run.field("message").contains("known: setImage"), "message:\n" + run.toString());
    }

    @Test
    @DisplayName("apply without a manifest is refused")
    void refusesApplyWithoutAManifest() throws Exception {
        AtomResult run = atom.bin("kubectl", FAKE_KUBECTL)
                .input("action", "apply")
                .run();

        assertNotEquals(0, run.exit(), "apply without a manifest must be refused");
    }

    @Test
    @DisplayName("a kubectl binary that is not there is an environment error")
    void reportsAMissingKubectl() throws Exception {
        AtomResult run = atom.input("kubectlBinary", "no-such-dir/kubectl").run();

        assertNotEquals(0, run.exit(), "missing kubectl must fail");
        assertEquals("5", run.field("errorType"),
                "missing kubectl should be ENVIRONMENT(5), got:\n" + run.toString());
    }
}
