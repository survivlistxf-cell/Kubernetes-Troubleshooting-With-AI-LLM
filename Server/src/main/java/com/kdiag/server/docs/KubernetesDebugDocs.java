package com.kdiag.server.docs;

import org.springframework.stereotype.Component;

/**
 * RAG-lite: small embedded guidance derived from Kubernetes docs
 * https://kubernetes.io/docs/tasks/debug/debug-application/
 *
 * We embed a short excerpt-like summary and always include the canonical link.
 * Later: fetch and cache the page content, chunk it, and do retrieval.
 */
@Component
public class KubernetesDebugDocs {

    private static final String URL = "https://kubernetes.io/docs/tasks/debug/debug-application/";

    public String getGuidanceSnippet() {
        // Keep this short to avoid blowing prompt size.
        return "Kubernetes app debugging checklist (from official docs):\n"
                + "- Start by checking current state: `kubectl get pods -n <ns>` and look at STATUS/RESTARTS.\n"
                + "- Inspect events and container lifecycle: `kubectl describe pod <pod> -n <ns>` (Events section is critical).\n"
                + "- Read container logs: `kubectl logs <pod> -n <ns> [-c <container>] --tail=200` and for crashes use `--previous`.\n"
                + "- For shell/debug inside a running container: `kubectl exec -it <pod> -n <ns> -- <cmd>` (or a shell).\n"
                + "- For Services/networking: verify Endpoints/Selectors and test connectivity from inside cluster.\n"
                + "Source: " + URL;
    }
}
