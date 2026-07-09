# s02 | mode=dynamic | run=1 | 16.3s | needs_search=0

**Q:** My pod 's02-imagepull' in namespace 'kubexplain-eval' will not start. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** image cannot be pulled (bad name/tag or missing credentials); fix image reference

---

## Most Likely reasons why **s02‑imagepull** never starts (ordered by probability)

### 1. Image‑pull failure (ErrImagePull / ImagePullBackOff)  
**Why it happens**  
* The image name or tag is misspelled or does not exist in the registry.  
* The registry is private and the pod has no valid `imagePullSecret`.  
* The secret exists but is mis‑named, in the wrong namespace, or contains bad credentials.  
* The node cannot reach the registry (DNS failure, firewall, proxy, or no internet).  
* `imagePullPolicy: Always` forces a pull of a tag that was never pushed (e.g. `latest` that isn’t present).

**How to verify (when you can run commands)**  
* Look at the pod’s **STATUS** – it will show `ErrImagePull` or `ImagePullBackOff`.  
* `kubectl describe pod s02-imagepull -n kubexplain-eval` – the **Events** section will contain messages such as “Failed to pull image …” and the exact error (e.g. “repository not found”, “authentication required”, “network timeout”).  
* Check the image reference in the pod spec (`kubectl get pod … -o yaml`) and compare it with the image that actually exists in the registry.  
* Verify the secret: `kubectl get secret <secret‑name> -n kubexplain-eval` and ensure it contains a Docker config (`.dockerconfigjson`).  
* From a node (or a temporary pod) try `docker pull <image>` or `crictl pull <image>` to see if the registry is reachable.

**How to fix**  
* Correct the image name/tag in the pod/deployment manifest and re‑apply.  
* If the registry is private, create or update an `imagePullSecret` with valid credentials and reference it in the pod spec (`imagePullSecrets`).  
* Ensure the secret lives in the same namespace as the pod.  
* If the node cannot reach the registry, fix DNS, proxy, firewall, or VPC routing.  
* Change `imagePullPolicy` to `IfNotPresent` for tags that already exist locally, or push the missing tag to the registry.

---

### 2. Scheduling failure (FailedScheduling) – pod never gets onto a node  
**Why it happens**  
* No node has enough CPU / memory (or other requested resources) to satisfy the pod’s **requests**.  
* Node selectors, affinity/anti‑affinity rules, or required tolerations prevent placement.  
* The cluster is out of nodes (all are `NotReady` or cordoned).  
* A `PodDisruptionBudget` or `ResourceQuota` blocks creation of additional replicas.

**How to verify**  
* `kubectl describe pod …` – the **Events** will show “FailedScheduling” with reasons such as “0/3 nodes are available: 3 Insufficient cpu, 3 node(s) had taint …”.  
* `kubectl get nodes` – check node **STATUS** (Ready/NotReady) and resource usage (`kubectl top node`).  
* Review the pod spec for `nodeSelector`, `affinity`, `tolerations`, and resource **requests**.  
* Look at any `ResourceQuota` objects in the namespace (`kubectl get quota -n kubexplain-eval`).

**How to fix**  
* Reduce the pod’s resource **requests** or add more capacity (scale the node pool, add nodes).  
* Adjust or remove restrictive `nodeSelector`/affinity rules, or add matching tolerations.  
* If a quota is blocking, increase the quota or delete unused resources.  
* Un‑cordon or restart unhealthy nodes, or fix node‑level problems (kubelet, network, etc.).

---

### 3. Node‑level problems (node NotReady, taints, or missing runtime)  
**Why it happens**  
* The node the pod is bound to is `NotReady` (kubelet stopped, network partition, disk pressure).  
* The node has a taint that the pod does not tolerate (e.g., `node.kubernetes.io/unreachable`).  
* The container runtime on the node cannot start containers (corrupted CRI socket, out‑of‑disk).

**How to verify**  
* `kubectl describe node <node‑name>` – look for **Conditions** (Ready=False) and recent **Events** (e.g., “Node has disk pressure”).  
* Check the pod’s **Node** field; then inspect that node’s taints (`kubectl get node -o yaml`).  
* Review node‑level logs (kubelet, container runtime) if you have node access.

**How to fix**  
* Resolve the underlying node issue: restart kubelet, free disk space, fix network, or replace the node.  
* Add a matching toleration to the pod spec if the taint is intentional.  
* If the node is permanently unhealthy, cordon and drain it, then let the scheduler place the pod on a healthy node.

---

### 4. Admission‑webhook or finalizer blocking pod creation  
**Why it happens**  
* A validating or mutating webhook rejects the pod (e.g., policy that disallows certain images).  
* A finalizer is attached to the pod (rare) and a webhook prevents its removal, leaving the pod stuck in `Terminating`.

**How to verify**  
* `kubectl describe pod …` – events may contain “admission webhook “…“ denied the request”.  
* List webhook configurations in the cluster (`kubectl get validatingwebhookconfigurations` / `mutatingwebhookconfigurations`) and look for ones that target Pods.  
* Check the pod’s `metadata.finalizers` field (`kubectl get pod -o yaml`).

**How to fix**  
* Adjust or disable the offending webhook (coordinate with the team that owns it).  
* Remove or correct the finalizer (e.g., `kubectl patch pod … --type=json -p='[{"op":"remove","path":"/metadata/finalizers"}]'`).  
* If the webhook is enforcing a policy you must comply with, change the pod spec to satisfy it (e.g., use an allowed image).

---

### 5. Incorrect pod spec that leaves the container in **Waiting** (but not image‑pull)  
**Why it happens**  
* The container’s `command` or `args` are malformed, causing the container runtime to wait for a non‑existent executable.  
* The container image is **distroless** and lacks a shell, so attempts to `exec` into it fail (though the pod may still start).  
* An init container never completes (e.g., stuck on a script or waiting for a service).

**How to verify**  
* `kubectl describe pod …` – look at the **Containers** section for `State: Waiting` and the **Reason** (e.g., `CrashLoopBackOff`, `CreateContainerConfigError`).  
* Examine the pod’s YAML to see the `command`, `args`, and any `initContainers`.  
* If an init container is present, check its status separately.

**How to fix**  
* Correct the `command`/`args` fields or remove them so the image’s default entrypoint runs.  
* If you need a shell, add an ephemeral debugging container (`kubectl debug … --image=busybox`) or rebuild the image with debugging tools.  
* Fix or remove the problematic init container, or add appropriate readiness/liveness probes.

---

## Quick verification checklist (when you regain cluster access)

1. **Pod status & events** – `kubectl describe pod s02-imagepull -n kubexplain-eval`.  
2. **Image reference** – confirm name, tag, and registry.  
3. **Image pull secret** – ensure it exists, is correctly named, and is referenced.  
4. **Node health** – `kubectl get node` and `kubectl describe node <node>`.  
5. **Scheduling constraints** – look for resource requests, selectors, taints, quotas.  
6. **Webhooks / finalizers** – check for admission‑webhook denial messages.  
7. **Container state** – see if it’s `Waiting`, `CrashLoopBackOff`, or `Running` but misbehaving.

Address the first failing check you encounter; in most cases the root cause will be an image‑pull problem, followed by scheduling or node issues. Fixing the identified problem and redeploying the pod should allow it to start normally.
