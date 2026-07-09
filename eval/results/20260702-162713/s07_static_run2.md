# s07 | mode=static | run=2 | 13.8s | needs_search=0

**Q:** My pod 's07-missing-cm' in namespace 'kubexplain-eval' fails to start its container. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** referenced ConfigMap does not exist; create it or fix the reference

---

## Most Likely root‑cause categories (ordered by probability)

| # | Likely cause | Why it’s a common reason for a pod that never gets past **Waiting** |
|---|--------------|---------------------------------------------------------------------|
| 1 | **Missing or unreadable ConfigMap** (`s07-missing-cm` hints that the pod expects a ConfigMap that isn’t present or can’t be mounted) | The pod will stay in *Waiting* with a reason such as `CreateContainerConfigError` or `MountVolume.SetUpFailed`. |
| 2 | **Image‑pull failure** (wrong image name, missing tag, private registry auth, network issue) | The container state will be `Waiting` with reason `ErrImagePull` or `ImagePullBackOff`. |
| 3 | **CrashLoopBackOff / immediate container exit** (application code crashes on start) | The pod may briefly move to *Running* then back to *Waiting* with reason `CrashLoopBackOff`. |
| 4 | **Insufficient resources** (CPU / memory requests exceed what any node can provide) | Scheduler may place the pod, but the kubelet will keep it in *Waiting* with reason `FailedScheduling` or `Insufficient memory`. |
| 5 | **Node‑selector / taint‑toleration mismatch** (pod is bound to a node that cannot run it) | The pod stays *Pending* with events like `0/3 nodes are available: 1 node(s) didn’t match node selector, 2 node(s) had taints that the pod didn’t tolerate`. |
| 6 | **Security‑context / PSP / SELinux denial** (pod tries to run as a user it isn’t allowed) | The container will be `Waiting` with reason `RunContainerError` and a message about permission denied. |
| 7 | **Init‑container failure** (an init container never completes) | The pod remains in *Init:0/1* or *Waiting* with the init container’s failure reason. |
| 8 | **Admission‑webhook or mutating webhook rejecting the pod** (e.g., a policy that blocks the pod) | The pod will be `Waiting` with reason `FailedCreate` and an event mentioning the webhook. |

---

## How to verify each cause (once you have cluster access)

1. **Check the pod’s status and events**  
   *Look at `kubectl describe pod s07-missing-cm -n kubexplain-eval`* – the **Reason** field under **State** and the **Events** section will usually point directly to the problem (e.g., `CreateContainerConfigError`, `ErrImagePull`, `FailedScheduling`).

2. **Missing ConfigMap**  
   * Verify that the ConfigMap referenced in the pod spec actually exists: `kubectl get cm <name> -n kubexplain-eval`.  
   * Confirm the mount path and key names match the ConfigMap data.  
   * If the ConfigMap is absent, create it or correct the pod spec to reference the right name.

3. **Image‑pull problems**  
   * Look for events with `ErrImagePull` or `ImagePullBackOff`.  
   * Check the image name/tag in the pod spec.  
   * If the registry is private, ensure a proper `imagePullSecret` is defined and the secret exists.  
   * Test pulling the image manually on a node (or locally) to see the exact error.

4. **CrashLoopBackOff / immediate exit**  
   * Examine the container logs: `kubectl logs s07-missing-cm -n kubexplain-eval`.  
   * Look for stack traces, missing environment variables, or mis‑configured command‑line flags.  
   * If the container exits instantly, try running the same image locally with the same command to reproduce the failure.

5. **Resource constraints**  
   * In the pod description, check the **Requests** and **Limits**.  
   * Compare them with node capacity (`kubectl describe node <node>`).  
   * If requests are too high, lower them or add more nodes / increase node capacity.

6. **Node‑selector / taints**  
   * Review `nodeSelector`, `affinity`, and `tolerations` sections of the pod spec.  
   * Use `kubectl get nodes` to see which nodes have matching labels and which have taints.  
   * Adjust the selector/affinity or add appropriate tolerations.

7. **Security‑context / PSP / SELinux**  
   * Look for `RunContainerError` with a message about permission denied or SELinux.  
   * Verify the `runAsUser`, `runAsGroup`, `fsGroup`, and any `seLinuxOptions`.  
   * Ensure the pod complies with any PodSecurityPolicy or OPA/Gatekeeper policies in place.

8. **Init‑container failure**  
   * The pod description will list the init container status.  
   * Check its logs (`kubectl logs s07-missing-cm -c <init‑container> -n kubexplain-eval`).  
   * Fix the command, image, or required resources for the init container.

9. **Admission‑webhook rejection**  
   * Events will mention the webhook name and the rejection message.  
   * Review the webhook configuration (`kubectl get validatingwebhookconfigurations` or `mutatingwebhookconfigurations`).  
   * Either adjust the pod spec to satisfy the webhook or temporarily disable the webhook for testing.

---

## Typical remediation steps (once the cause is identified)

| Cause | Fix |
|-------|-----|
| Missing ConfigMap | `kubectl create configmap <name> --from-file=... -n kubexplain-eval` or edit the pod/deployment to reference the correct ConfigMap name. |
| Image‑pull error | Correct the image name/tag, add or fix `imagePullSecrets`, ensure network connectivity to the registry, or push the image to a reachable registry. |
| CrashLoopBackOff | Update the container image with a bug‑fix, add missing env vars/flags, or change the command to a safe entrypoint. |
| Insufficient resources | Lower resource requests/limits, add more nodes, or enable cluster autoscaling. |
| Node selector / taints | Align pod’s `nodeSelector`/`affinity` with node labels, or add appropriate tolerations for existing taints. |
| Security context / PSP | Adjust `runAsUser`, `fsGroup`, or disable/modify the restrictive PSP/policy. |
| Init‑container failure | Fix the init container’s command or image, ensure it has enough resources, or remove it if not needed. |
| Admission webhook | Modify the pod spec to comply with the webhook’s rules, or temporarily disable the webhook for debugging. |

---

### Quick checklist for when you regain access

1. `kubectl describe pod s07-missing-cm -n kubexplain-eval` → note **Reason** and **Events**.  
2. Verify any referenced ConfigMaps, Secrets, and imagePullSecrets exist.  
3. Look at container logs for runtime errors.  
4. Check resource requests vs node capacity.  
5. Confirm node selector/affinity/taints alignment.  
6. Review security context and any active PodSecurityPolicies or OPA policies.  
7. If an init container is present, inspect its logs.  
8. Search events for webhook rejections.

Address the first failing check you encounter; fixing that most often resolves the *Waiting* state and lets the pod start normally.
