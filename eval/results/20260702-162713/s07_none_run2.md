# s07 | mode=none | run=2 | 16.0s | needs_search=0

**Q:** My pod 's07-missing-cm' in namespace 'kubexplain-eval' fails to start its container. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** referenced ConfigMap does not exist; create it or fix the reference

---

## Most likely reasons the pod **`s07‑missing‑cm`** can’t start (ordered by probability)

| # | Likely cause | Why it’s the top suspect | How to verify (when you have cluster access) | How to fix |
|---|--------------|--------------------------|---------------------------------------------|------------|
| 1 | **Referenced ConfigMap does not exist or is not mounted correctly** | The pod name itself contains *missing‑cm* – a common pattern used in exercises to illustrate a missing ConfigMap. If a container tries to read a file that isn’t present, the process will exit immediately and the pod will go into `CrashLoopBackOff`. | ```bash\nkubectl -n kubexplain-eval get pod s07-missing-cm -o yaml | grep -i configmap\nkubectl -n kubexplain-eval describe pod s07-missing-cm | grep -i \"ConfigMap\"\n```<br>Check the **Events** section for messages like `Error: configmap "my‑cm" not found`. Also inspect the container’s start‑up logs (`kubectl logs …`) – they often contain “no such file or directory”. | *Create or correct the ConfigMap*:<br>```bash\nkubectl -n kubexplain-eval create configmap <name> --from-file=…\n# or, if the ConfigMap exists but the name is wrong, edit the pod/deployment:\nkubectl -n kubexplain-eval edit <controller>\n```<br>Make sure the `volumeMounts` path matches the key name in the ConfigMap. |
| 2 | **Image pull failure (wrong tag, private registry, missing credentials)** | If the container image cannot be pulled, the pod never reaches the “Running” state and stays in `ImagePullBackOff` or `ErrImagePull`. This is the second‑most common start‑up blocker. | ```bash\nkubectl -n kubexplain-eval describe pod s07-missing-cm | grep -i \"Pull\" -A3\n```<br>Look for events such as `Failed to pull image "repo/app:tag": rpc error: …`. | *Fix the image reference* (correct repository/tag).<br>*If the registry is private*: create a Docker secret and reference it in the pod (`imagePullSecrets`).<br>```bash\nkubectl -n kubexplain-eval create secret docker-registry reg‑cred --docker-server=… --docker-username=… --docker-password=… --docker-email=…\n``` |
| 3 | **Insufficient resources (CPU / memory) on the node** | When the scheduler places the pod on a node that cannot satisfy its `requests`/`limits`, the pod may stay in `Pending` with a `FailedScheduling` event, or it may start and be OOM‑killed immediately. | ```bash\nkubectl -n kubexplain-eval describe pod s07-missing-cm | grep -i \"Insufficient\"\n```<br>Also check node capacity: `kubectl top nodes` (if metrics‑server is installed). | *Reduce the pod’s resource requests/limits* or *add more capacity* (scale the node pool, increase quotas). |
| 4 | **Container command / entrypoint error** | If the container’s `command` or `args` are wrong, the process exits with a non‑zero code, leading to `CrashLoopBackOff`. This is common when a ConfigMap is mounted as a script and the script is missing or not executable. | ```bash\nkubectl -n kubexplain-eval logs s07-missing-cm --previous\n```<br>Look for “command not found”, “permission denied”, or stack traces. | *Correct the command* in the pod spec, or ensure the script file is present and executable (`chmod +x`). |
| 5 | **Init container failure** | If the pod has one or more init containers and any of them fails, the main container never starts. The pod will stay in `Init:Error` or `Init:CrashLoopBackOff`. | ```bash\nkubectl -n kubexplain-eval describe pod s07-missing-cm | grep -i \"Init\"\n``` | *Fix the init container* (image, command, volume mounts) or remove it if it’s not needed. |
| 6 | **Pod security / admission policy violation** | Policies such as PSP, OPA/Gatekeeper, or Seccomp may reject the pod (e.g., privileged flag, hostPath, runAsUser). The pod will be `Rejected` with an event like `failed to admit pod`. | ```bash\nkubectl -n kubexplain-eval describe pod s07-missing-cm | grep -i \"admission\"\n``` | *Adjust the pod spec to comply* (remove privileged flag, set allowed `runAsUser`, use allowed volume types) or update the policy if the behavior is intended. |
| 7 | **Node‑level problem (cordoned, unschedulable, network issue)** | A node that is `cordoned` or has a network problem can prevent the pod from pulling images or mounting volumes, causing start‑up failures. | ```bash\nkubectl get nodes -o wide | grep -i \"SchedulingDisabled\"\n``` | *Uncordon the node* (`kubectl uncordon <node>`), or move the pod to a healthy node (e.g., add a node selector/affinity). |
| 8 | **Missing secret (TLS certs, pull secret, env var)** | If a secret referenced in `envFrom`, `volume`, or `imagePullSecrets` does not exist, the pod will fail during creation. | ```bash\nkubectl -n kubexplain-eval describe pod s07-missing-cm | grep -i \"Secret\"\n``` | *Create the required secret* or correct the name in the pod spec. |
| 9 | **Readiness / Liveness probe causing immediate restart** | Aggressive probes can kill a container before it has a chance to start, leading to a rapid `CrashLoopBackOff`. | ```bash\nkubectl -n kubexplain-eval describe pod s07-missing-cm | grep -i \"probe\"\n``` | *Relax the probe thresholds* (`initialDelaySeconds`, `periodSeconds`) or temporarily remove the probes to confirm. |
|10| **Cluster‑wide quota or limit range violation** | If the namespace exceeds its `ResourceQuota` or a `LimitRange` blocks the pod’s requested resources, the pod will be rejected. | ```bash\nkubectl -n kubexplain-eval get quota\nkubectl -n kubexplain-eval get limitrange\n``` | *Adjust the quota* or *reduce the pod’s requests/limits* to fit within the allowed range. |

---

## How to verify the exact cause (when you regain access)

1. **Inspect the pod’s status and events**  
   ```bash
   kubectl -n kubexplain-eval describe pod s07-missing-cm
   ```
   - Look at the **Events** section for messages about missing ConfigMaps, image pull errors, scheduling failures, or admission rejections.

2. **Check container logs** (including previous attempts)  
   ```bash
   kubectl -n kubexplain-eval logs s07-missing-cm          # current attempt
   kubectl -n kubexplain-eval logs s07-missing-cm --previous   # last failed attempt
   ```

3. **Validate referenced resources**  
   - ConfigMaps: `kubectl -n kubexplain-eval get cm` and compare names.  
   - Secrets: `kubectl -n kubexplain-eval get secret`.  
   - Image: `kubectl -n kubexplain-eval get pod s07-missing-cm -o jsonpath='{.spec.containers[*].image}'`.

4. **Review resource requests/limits**  
   ```bash
   kubectl -n kubexplain-eval get pod s07-missing-cm -o yaml | grep -A5 resources
   ```

5. **If init containers exist**  
   ```bash
   kubectl -n kubexplain-eval get pod s07-missing-cm -o yaml | grep -i initContainers -A10
   ```

6. **Check node health** (once you have node access)  
   ```bash
   kubectl get nodes
   kubectl describe node <node‑name>
   ```

---

## Quick remediation checklist (apply in order of the most probable cause)

1. **Create / correct the missing ConfigMap**  
   ```bash
   kubectl -n kubexplain-eval create configmap <name> --from-file=path/
   # or edit the pod/deployment to reference the correct ConfigMap name
   ```

2. **Fix image pull problems**  
   - Verify the image name/tag.  
   - Add/refresh `imagePullSecrets` for private registries.

3. **Adjust resources**  
   - Lower `requests`/`limits` or request more node capacity.

4. **Correct container command / script**  
   - Ensure the entrypoint script exists, is executable, and has the right path.

5. **Resolve init‑container failures**  
   - Check its logs, image, and volume mounts.

6. **Align with security policies**  
   - Remove disallowed fields (e.g., `privileged: true`) or update the policy.

7. **Uncordon or replace unhealthy nodes** if the pod is stuck on a bad node.

8. **Create any missing Secrets** (TLS, env vars, pull secrets).

9. **Relax probes** if they are killing the container prematurely.

10. **Stay within quotas/limits** by adjusting the pod spec or increasing the quota.

---

### Bottom line

The **most probable root cause** is a **missing or mis‑named ConfigMap** (the pod name itself hints at that). Verify the ConfigMap first; if it’s present, move down the list—image pull, resources, command errors, init containers, and policy violations are the next common blockers. When you regain access, the `kubectl describe pod …` output will point you directly to the offending event, and the remediation steps above will get the pod running again.
