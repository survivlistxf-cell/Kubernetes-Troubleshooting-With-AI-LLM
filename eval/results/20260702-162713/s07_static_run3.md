# s07 | mode=static | run=3 | 14.5s | needs_search=0

**Q:** My pod 's07-missing-cm' in namespace 'kubexplain-eval' fails to start its container. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** referenced ConfigMap does not exist; create it or fix the reference

---

## Most likely reasons a pod `s07‑missing‑cm` in **kubexplain‑eval** cannot start its container  

| # | Likely cause (probability ≈ high → low) | How to verify (what you’ll look for) | How to fix (once you have cluster access) |
|---|----------------------------------------|--------------------------------------|-------------------------------------------|
| 1 | **Referenced ConfigMap does not exist** (the pod name itself hints at a missing ConfigMap) | `kubectl describe pod s07-missing-cm -n kubexplain-eval` – look for events such as **`MountVolume.SetUp`** or **`CreateContainerConfigError`** with a message like *“configmap “my‑cm” not found”*. Also check the pod spec (`volumes:` or `envFrom:`) for a ConfigMap name. | *Create* the missing ConfigMap (or correct the name). Example: `kubectl create configmap my‑cm --from-file=... -n kubexplain-eval`. If the ConfigMap is no longer needed, remove the reference from the pod spec (edit the Deployment/StatefulSet/Pod). |
| 2 | **Image pull failure** (wrong image name, missing tag, private registry without credentials) | In `kubectl describe pod …` look for **`ErrImagePull`** or **`ImagePullBackOff`** events. The container state will be *Waiting* with `Reason: ErrImagePull`. | Verify the image name/tag and that it is reachable (`docker pull …` from a node). If the registry is private, create or update an ImagePullSecret and reference it in the pod spec (`imagePullSecrets:`). |
| 3 | **Init container failure** (init container exits non‑zero, cannot complete) | `kubectl describe pod …` shows the init container status. Look for **`Init:Error`** or **`Init:CrashLoopBackOff`** and the corresponding log output (`kubectl logs <pod> -c <init‑container>`). | Fix the init container command/args, ensure required resources (ConfigMaps, Secrets, volumes) are present, or remove the init container if it is no longer needed. |
| 4 | **Insufficient resources on any node** (CPU/Memory request > available) | Scheduler events such as **`FailedScheduling`** with message *“0/3 nodes are available: 3 Insufficient cpu”*. The pod will stay in **Pending** rather than **Running**, but if it was scheduled and then evicted it can appear as *Waiting*. | Reduce the pod’s resource requests/limits, add more node capacity, or enable cluster autoscaling. |
| 5 | **Node selector / taints & tolerations mismatch** (pod cannot be placed) | Scheduler events: *“0/3 nodes are available: 3 node(s) didn’t match node selector”, “node(s) had taint … that the pod didn’t tolerate”*. | Adjust the pod’s `nodeSelector`, `affinity`, or add appropriate `tolerations`. |
| 6 | **Application crash (CrashLoopBackOff) due to bad command, missing env var, or runtime error** | Container state shows **`CrashLoopBackOff`**. `kubectl logs s07-missing-cm -n kubexplain-eval` will reveal the error output (e.g., *“No such file or directory”, “missing required env VAR”*). | Correct the container command/args, add missing environment variables or files, or update the image to a working version. |
| 7 | **Missing Secret or ServiceAccount permission** (pod tries to mount a secret that does not exist, or the SA cannot pull the image) | Events like *“secret “my‑secret” not found”* or *“serviceaccount token not found”*. Also check `kubectl get secret …` and the ServiceAccount spec. | Create the required Secret, or adjust the ServiceAccount to include needed imagePullSecrets or RBAC permissions. |
| 8 | **PodSecurityPolicy / Admission webhook rejecting the pod** (e.g., disallowed privileged flag) | Events with **`FailedCreate`** or **`Admission webhook`** errors. The pod may stay in *Pending* with a message from the webhook. | Modify the pod spec to comply with the policy (remove privileged flag, set allowed seccomp/profile, etc.) or adjust the PSP/OPA policy to permit the pod. |
| 9 | **Finalizer / deletion dead‑lock** (if the pod is stuck in *Terminating* after a restart) | `kubectl get pod s07-missing-cm -o yaml` shows a `metadata.finalizers` entry that never gets cleared. | Remove the finalizer manually (`kubectl patch pod … --type=json -p='[{"op":"remove","path":"/metadata/finalizers"}]'`) or fix the webhook that adds it. |

---

## How to verify each cause (once you can run commands)

1. **Describe the pod** – gives a concise view of state, events, and why the container is *Waiting* or *CrashLoopBackOff*  

   ```bash
   kubectl describe pod s07-missing-cm -n kubexplain-eval
   ```

2. **Check the pod’s YAML** – see exact references to ConfigMaps, Secrets, ImagePullSecrets, resources, node selectors, etc.  

   ```bash
   kubectl get pod s07-missing-cm -n kubexplain-eval -o yaml
   ```

3. **Inspect container logs** – for init containers and the main container (if it started at least once).  

   ```bash
   kubectl logs s07-missing-cm -n kubexplain-eval          # main container
   kubectl logs s07-missing-cm -n kubexplain-eval -c <init‑container>
   ```

4. **Verify referenced objects exist**  

   ```bash
   kubectl get configmap <name> -n kubexplain-eval
   kubectl get secret <name> -n kubexplain-eval
   ```

5. **Look at scheduler events** (if the pod is still pending)  

   ```bash
   kubectl get events -n kubexplain-eval --sort-by=.metadata.creationTimestamp
   ```

6. **Check node resources** (if you suspect insufficient CPU/Memory)  

   ```bash
   kubectl top nodes
   ```

7. **Validate ImagePullSecret** (if private registry)  

   ```bash
   kubectl get secret <pull‑secret> -n kubexplain-eval -o yaml
   ```

8. **Review admission webhook / PSP messages** (if events mention a webhook)  

   ```bash
   kubectl get validatingwebhookconfigurations
   kubectl get podsecuritypolicy
   ```

---

## Quick “first‑check” checklist (run in order)

1. **Describe pod → look for “ConfigMap … not found”**  
2. If not, look for **`ErrImagePull` / `ImagePullBackOff`**  
3. Check **init‑container status** (any `Error` or `CrashLoopBackOff`)  
4. Scan **events for `FailedScheduling`** (resource or node‑selector issues)  
5. Look for **`CrashLoopBackOff`** and fetch container logs  
6. Verify any **Secret** or **ServiceAccount** references  
7. Review **admission‑webhook / PSP** messages if present  

Fix the first problem you encounter; most pods that fail to start do so because a required ConfigMap or Secret is missing, or because the image cannot be pulled. Once that is resolved, the pod will usually transition to **Running**. If it still fails, move down the list.
