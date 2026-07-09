# s02 | mode=dynamic | run=3 | 15.7s | needs_search=0

**Q:** My pod 's02-imagepull' in namespace 'kubexplain-eval' will not start. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** image cannot be pulled (bad name/tag or missing credentials); fix image reference

---

**Most likely reasons why the pod `s02-imagepull` in namespace `kubexplain‑eval` is not starting (ordered by probability)**  

| # | Likely cause | How to verify (when you have cluster access) | How to fix |
|---|--------------|---------------------------------------------|------------|
| 1 | **Image‑pull failure (`ImagePullBackOff` / `ErrImagePull`)** – the pod name itself hints that the problem is pulling the container image. | <ul><li>`kubectl describe pod s02-imagepull -n kubexplain-eval` – look for events such as *Failed to pull image* and the **State** of the container (e.g. `Waiting` with `Reason: ImagePullBackOff`).</li><li>`kubectl get pod s02-imagepull -n kubexplain-eval -o yaml` – check the `image:` field, `imagePullPolicy`, and any `imagePullSecrets` referenced.</li></ul> | <ul><li>Confirm the image name (including tag) is correct and exists in the registry.</li><li>If the registry is private, make sure a valid `Secret` of type `kubernetes.io/dockerconfigjson` exists and is referenced in `imagePullSecrets`.</li><li>Check that the node can reach the registry (DNS, firewall, proxy). A quick test is to run a temporary pod on the same node and `docker pull` the image.</li><li>If the image tag was mistyped, update the pod spec (or Deployment) and redeploy.</li></ul> |
| 2 | **Insufficient resources on any node** – the scheduler cannot place the pod because the requested CPU/memory exceed what is available. | <ul><li>`kubectl describe pod s02-imagepull -n kubexplain-eval` – the **Events** section will show `FailedScheduling` with a message like *0/3 nodes are available: 3 Insufficient cpu*.</li><li>`kubectl get nodes -o wide` and `kubectl describe node <node>` – look at allocatable resources vs. current usage.</li></ul> | <ul><li>Reduce the pod’s `resources.requests`/`limits` or remove them if they are not needed.</li><li>Scale the cluster (add nodes) or free resources by evicting/downsizing other workloads.</li></ul> |
| 3 | **Node selector / taints & tolerations mismatch** – the pod is constrained to a node that has no matching node, so it stays **Pending**. | <ul><li>`kubectl describe pod s02-imagepull -n kubexplain-eval` – look for events like *0/3 nodes are available: 3 node(s) didn’t match node selector* or *node(s) had taints that the pod didn’t tolerate*.</li><li>Inspect the pod spec for `nodeSelector`, `affinity`, `tolerations` and compare with node labels/taints (`kubectl get nodes --show-labels`, `kubectl describe node <node>`).</li></ul> | <ul><li>Adjust the pod’s `nodeSelector`/`affinity` to match an existing node, or add the required label to a node.</li><li>Add the appropriate `tolerations` to the pod, or remove the taint from the node if it is not needed.</li></ul> |
| 4 | **Network/DNS problems reaching the image registry** – even with a correct image name and credentials, the node cannot resolve or contact the registry. | <ul><li>From a pod that can run on the same node (e.g. `kubectl run -it --rm busybox --image=busybox --restart=Never -- sh`), try `nslookup <registry-host>` or `wget <registry-host>`.</li><li>Check cluster‑wide DNS (`coredns`) health: `kubectl get pods -n kube-system -l k8s-app=kube-dns`.</li></ul> | <ul><li>Fix DNS configuration (CoreDNS ConfigMap) or add the registry’s hostname to `/etc/hosts` via a `hostAliases` entry.</li><li>If a proxy is required, ensure the node’s environment variables (`HTTP_PROXY`, `NO_PROXY`) are set correctly.</li></ul> |
| 5 | **Wrong `imagePullPolicy`** – e.g., `Always` on a rate‑limited public registry can cause repeated failures. | <ul><li>`kubectl get pod s02-imagepull -n kubexplain-eval -o yaml` – check `imagePullPolicy`. If it is `Always` and the image tag is `latest`, the kubelet will try to pull on every restart.</li></ul> | <ul><li>Set `imagePullPolicy: IfNotPresent` (or omit it, which defaults to `IfNotPresent` for non‑`:latest` tags).</li></ul> |
| 6 | **Init container failure** – an init container that cannot pull its image or exits with an error blocks the main container. | <ul><li>`kubectl describe pod s02-imagepull -n kubexplain-eval` – look for an `InitContainers` section with a `State: Waiting/Terminated` and a non‑zero exit code.</li></ul> | <ul><li>Fix the init container’s image, command, or resource requests, then redeploy.</li></ul> |
| 7 | **Pod spec typo (e.g., wrong `command` field) causing the container to exit immediately** – leads to `CrashLoopBackOff`. | <ul><li>`kubectl logs s02-imagepull -n kubexplain-eval` – see the termination message.</li><li>`kubectl describe pod …` – check the `Containers` section for `State: Terminated` with `Reason: Error`.</li></ul> | <ul><li>Correct the `command`/`args` fields in the pod spec and apply the updated manifest.</li></ul> |
| 8 | **Admission webhook or finalizer blocking pod creation** – rare, but can leave the pod stuck in `Terminating` or `Pending`. | <ul><li>`kubectl describe pod …` – look for events mentioning a webhook error or a finalizer that cannot be removed.</li></ul> | <ul><li>Disable or fix the offending webhook, or remove the finalizer manually (`kubectl patch pod … --type=json …`).</li></ul> |

---

### How to verify each cause (once you regain access)

1. **Run `kubectl describe pod …`** – the **Events** and **Containers** sections give the most direct clues (image pull errors, scheduling failures, init‑container status, etc.).
2. **Check pod status with `kubectl get pod … -o wide`** – shows `STATUS` (Pending, Running, CrashLoopBackOff, ImagePullBackOff, etc.).
3. **Inspect node resources and labels** (`kubectl get nodes`, `kubectl describe node <node>`).
4. **Look at the pod’s YAML** (`kubectl get pod … -o yaml`) to verify `image`, `imagePullPolicy`, `imagePullSecrets`, `resources`, `nodeSelector`, `affinity`, `tolerations`, and any `initContainers`.
5. **If the pod is in `ImagePullBackOff`**, also check the image registry logs (if you control the registry) and verify that the secret used for authentication is correct (`kubectl get secret <secret> -n kubexplain-eval -o yaml`).

---

### Quick “first‑check” checklist you can run when you’re back in the cluster

```bash
# 1. Basic status & events
kubectl describe pod s02-imagepull -n kubexplain-eval

# 2. Full spec for deeper inspection
kubectl get pod s02-imagepull -n kubexplain-eval -o yaml
```

If the **Events** show a message about *Failed to pull image* or *ImagePullBackOff*, start with the image‑pull fixes (steps 1‑5 above). If the events talk about *FailedScheduling* or *node(s) didn’t match node selector*, move to the resource/affinity checks (steps 2‑4). Continue down the table until the root cause is identified.
