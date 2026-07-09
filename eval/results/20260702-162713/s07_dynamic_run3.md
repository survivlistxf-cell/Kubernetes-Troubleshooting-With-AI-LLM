# s07 | mode=dynamic | run=3 | 11.5s | needs_search=0

**Q:** My pod 's07-missing-cm' in namespace 'kubexplain-eval' fails to start its container. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** referenced ConfigMap does not exist; create it or fix the reference

---

## Most Likely Reasons ‘s07‑missing‑cm’ won’t Start (ordered by probability)

1. **Missing or unreadable ConfigMap (or Secret) referenced by the pod**  
   *Why it’s likely*: The pod name itself (`s07‑missing‑cm`) hints that a ConfigMap is expected but not present. When a volume or env‑var source points to a non‑existent ConfigMap, the container stays in **Waiting** with reason `CreateContainerConfigError`.  

   *How to verify*  
   ```bash
   kubectl describe pod s07-missing-cm -n kubexplain-eval
   ```  
   Look for events such as:  

   - `Failed to create container …: configmap "my‑cm" not found`  
   - `CreateContainerConfigError` in the container state.  

   *How to fix*  

   - **Create the missing ConfigMap** (or correct the name).  
     ```bash
     kubectl create configmap my-cm --from-file=path/to/data -n kubexplain-eval
     ```  
   - If the ConfigMap already exists in another namespace, either move it or change the pod spec to reference the correct namespace (add `namespace:` under `configMap` reference).  
   - If the ConfigMap is optional, add `optional: true` to the volume/env source so the pod can start without it.

2. **Image‑pull failure (wrong image name, missing tag, private registry auth)**  
   *Why it’s likely*: A container that cannot pull its image stays in **Waiting** with reason `ErrImagePull` or `ImagePullBackOff`.  

   *How to verify*  

   ```bash
   kubectl describe pod s07-missing-cm -n kubexplain-eval
   ```  

   Check the container state for `ErrImagePull` / `ImagePullBackOff` and the accompanying message (e.g., “repository not found”, “authentication required”).  

   *How to fix*  

   - Confirm the image name and tag are correct.  
   - If the image lives in a private registry, create a Docker secret and reference it in the pod’s `imagePullSecrets`.  
   - Test pulling the image manually on a node (or locally with `docker pull …`).  

3. **CrashLoopBackOff due to application error**  
   *Why it’s likely*: Even if the pod starts, a mis‑configured command or missing runtime dependency can cause the container to exit repeatedly, leading to `CrashLoopBackOff`.  

   *How to verify*  

   ```bash
   kubectl logs s07-missing-cm -n kubexplain-eval --previous
   ```  

   Review the logs for stack traces, “command not found”, or other runtime errors.  

   *How to fix*  

   - Adjust the container command/args in the pod spec.  
   - Add a debugging container (ephemeral container) to inspect the filesystem or run the command manually.  
   - Ensure required files, environment variables, or side‑car services are present.  

4. **Insufficient resources (CPU / memory) causing scheduling or OOM**  
   *Why it’s likely*: If the pod requests more resources than any node can satisfy, it stays **Pending** with `FailedScheduling`. If it starts but exceeds its memory limit, it is killed and may re‑start, showing `OOMKilled`.  

   *How to verify*  

   - For scheduling: `kubectl describe pod …` → look for events like “0/3 nodes are available: 3 Insufficient memory”.  
   - For OOM: `kubectl describe pod …` → under container status see `Reason: OOMKilled`.  

   *How to fix*  

   - Reduce the resource requests/limits in the pod spec.  
   - Add more capacity to the cluster or enable cluster autoscaling.  

5. **Node selector / taint‑toleration mismatch**  
   *Why it’s likely*: If the pod specifies a `nodeSelector`, `nodeAffinity`, or requires a toleration that no node satisfies, the scheduler will keep it **Pending**.  

   *How to verify*  

   ```bash
   kubectl describe pod s07-missing-cm -n kubexplain-eval
   ```  

   Look for events such as “0/3 nodes are available: 3 node(s) didn’t match node selector”.  

   *How to fix*  

   - Adjust or remove the selector/affinity rules.  
   - Add the required label to a node, or add a matching toleration if a taint is present.  

6. **PodSecurityPolicy / Admission webhook rejection**  
   *Why it’s likely*: A restrictive PSP or a validating webhook can reject the pod creation, leaving it in **Pending** with a `FailedCreate` event.  

   *How to verify*  

   - `kubectl describe pod …` → events like “failed to create pod sandbox: pod violates PodSecurityPolicy”.  
   - Check cluster‑wide PSPs or webhook configurations (`kubectl get psp`, `kubectl get validatingwebhookconfiguration`).  

   *How to fix*  

   - Grant the pod a service account that satisfies the PSP.  
   - Modify the PSP to allow the required capabilities (e.g., privileged, hostPath).  
   - Adjust or disable the offending webhook if it’s overly strict.  

7. **Finalizer / deletion deadlock (if the pod is stuck in Terminating)**  
   *Why it’s less likely for a start‑up failure* but still possible if a previous deletion left a finalizer.  

   *How to verify*  

   ```bash
   kubectl get pod s07-missing-cm -n kubexplain-eval -o yaml | grep -i finalizers
   ```  

   *How to fix*  

   - Remove the finalizer manually (`kubectl patch pod … --type=json -p='[{"op":"remove","path":"/metadata/finalizers"}]'`).  

---

### Quick Verification Checklist (run when you regain cluster access)

| Step | Command | What to look for |
|------|---------|------------------|
| 1️⃣  | `kubectl describe pod s07-missing-cm -n kubexplain-eval` | Events, container state reasons (`CreateContainerConfigError`, `ErrImagePull`, `FailedScheduling`, etc.) |
| 2️⃣  | `kubectl get cm -n kubexplain-eval` | Does the referenced ConfigMap exist? |
| 3️⃣  | `kubectl logs s07-missing-cm -n kubexplain-eval --previous` | Application error output if the container started and crashed |
| 4️⃣  | `kubectl get pod s07-missing-cm -n kubexplain-eval -o yaml | grep -A5 resources` | Resource requests/limits |
| 5️⃣  | `kubectl get nodes -o wide` | Verify node labels, taints, and available capacity |
| 6️⃣  | `kubectl get psp` / `kubectl get validatingwebhookconfiguration` | Any policies that could reject the pod |
| 7️⃣  | `kubectl get pod s07-missing-cm -n kubexplain-eval -o yaml | grep finalizers` | Stuck finalizer |

Address the first issue you discover; in most cases the missing ConfigMap will be the root cause given the pod’s name. Once the ConfigMap (or other missing dependency) is supplied, the pod should transition to **Running** unless another problem (image pull, crash loop, etc.) surfaces.
