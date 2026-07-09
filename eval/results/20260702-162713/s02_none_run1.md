# s02 | mode=none | run=1 | 17.6s | needs_search=0

**Q:** My pod 's02-imagepull' in namespace 'kubexplain-eval' will not start. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** image cannot be pulled (bad name/tag or missing credentials); fix image reference

---

## Most likely reasons a pod named **`s02-imagepull`** in namespace **`kubexplain-eval`** will stay in a non‑Running state  

Below is a prioritized list (most common → less common).  For each item you’ll see:

* **Why it happens** – the typical root‑cause.  
* **How to verify it** – the `kubectl` checks you would run when you regain access.  
* **How to fix it** – the change you normally make.

---

### 1. Image‑pull problems (`ImagePullBackOff` / `ErrImagePull`)  
**Why:**  
* The image name, tag, or registry URL is wrong.  
* The registry requires authentication and the pod has no `imagePullSecret`.  
* The registry is unreachable (network, DNS, firewall).  

**Verify (when you can run commands):**  
```bash
kubectl get pod s02-imagepull -n kubexplain-eval -o wide
kubectl describe pod s02-imagepull -n kubexplain-eval
```
* Look for the **`State: Waiting`** line that shows `ImagePullBackOff` or `ErrImagePull`.  
* In the `Events` section you’ll see messages such as “Failed to pull image …” or “pull secret not found”.  

**Fix:**  
* Correct the image reference in the pod/deployment spec (`image: repo/name:tag`).  
* If the registry is private, create a secret and reference it:  

  ```bash
  kubectl create secret docker-registry my-reg-secret \
      --docker-server=REGISTRY_URL \
      --docker-username=USER \
      --docker-password=PASS \
      --docker-email=EMAIL
  ```
  Then add `imagePullSecrets: [my-reg-secret]` to the pod spec.  
* Verify network connectivity to the registry from a node (e.g., `curl` or `dig`).  

---

### 2. Insufficient resources (CPU / memory) → pod stays **`Pending`**  
**Why:**  
* The pod requests more CPU or memory than any node can satisfy.  
* A `ResourceQuota` or `LimitRange` in the namespace caps the amount you can request.  

**Verify:**  
```bash
kubectl describe pod s02-imagepull -n kubexplain-eval
kubectl get nodes -o wide
kubectl describe quota -n kubexplain-eval
```
* In the pod description, check the **`Requests:`** and **`Limits:`** fields.  
* In the node list, look for `Allocatable` resources and compare.  
* If a quota is blocking, the `Events` will show “exceeded quota”.  

**Fix:**  
* Lower the requested resources in the pod spec.  
* Add more capacity to the cluster (scale nodes) or adjust node sizing.  
* If a quota is too strict, raise it or move the pod to a different namespace.  

---

### 3. Scheduling constraints (node selectors, taints & tolerations, affinity)  
**Why:**  
* The pod has a `nodeSelector`, `nodeAffinity`, or `podAffinity` that no node satisfies.  
* Nodes are tainted (e.g., `key=value:NoSchedule`) and the pod lacks a matching toleration.  

**Verify:**  
```bash
kubectl describe pod s02-imagepull -n kubexplain-eval
kubectl get nodes -o jsonpath='{range .items[*]}{.metadata.name} {.spec.taints}\n{end}'
```
* In the pod description, look for `Node-Selectors`, `Affinity`, and `Tolerations`.  
* In the node output, see any `taints`.  

**Fix:**  
* Adjust or remove the selector/affinity rules so that at least one node matches.  
* Add a toleration to the pod if the node’s taint is intentional:  

  ```yaml
  tolerations:
  - key: "key"
    operator: "Equal"
    value: "value"
    effect: "NoSchedule"
  ```  

* Or remove the taint from the node (`kubectl taint node <node> key=value:NoSchedule-`).  

---

### 4. Init‑container failure  
**Why:**  
* An init container exits with a non‑zero status, preventing the main container from starting.  

**Verify:**  
```bash
kubectl describe pod s02-imagepull -n kubexplain-eval
kubectl logs s02-imagepull -n kubexplain-eval -c <init‑container-name>
```
* The `Events` will show “Init:Error” and the container name.  

**Fix:**  
* Inspect the init‑container logs to understand the error (e.g., missing file, permission).  
* Correct the command, image, or volume mounts used by the init container.  
* If the init step is no longer needed, remove it from the pod spec.  

---

### 5. CrashLoopBackOff / rapid restarts of the main container  
**Why:**  
* The container starts, crashes, and is restarted repeatedly (often due to missing config, bad command, or runtime error).  

**Verify:**  
```bash
kubectl get pod s02-imagepull -n kubexplain-eval -o jsonpath='{.status.containerStatuses[*].state}'
kubectl logs s02-imagepull -n kubexplain-eval --previous
```
* Look for `CrashLoopBackOff` in the status and view the **previous** container logs for the failure trace.  

**Fix:**  
* Fix the underlying application error (e.g., correct environment variables, mount required ConfigMaps/Secrets, adjust command line).  
* Add a `livenessProbe`/`readinessProbe` only after the container runs correctly, to avoid premature restarts.  

---

### 6. Missing ConfigMap or Secret volume / wrong reference  
**Why:**  
* The pod references a ConfigMap or Secret that does not exist or has a typo.  

**Verify:**  
```bash
kubectl describe pod s02-imagepull -n kubexplain-eval
kubectl get configmap -n kubexplain-eval
kubectl get secret -n kubexplain-eval
```
* In the pod description, missing resources appear as `Error: configmap "…" not found`.  

**Fix:**  
* Create the missing ConfigMap/Secret or correct the name in the pod spec.  

---

### 7. PodSecurityPolicy / SecurityContext violations  
**Why:**  
* The cluster enforces a PSP (or the newer Pod Security Standards) that blocks the pod’s requested privileges (e.g., running as root, privileged mode).  

**Verify:**  
```bash
kubectl describe pod s02-imagepull -n kubexplain-eval
kubectl get psp   # if PSP is still enabled
kubectl get podsecuritypolicy
```
* Look for events like “failed to create pod sandbox: ... forbidden: ...”.  

**Fix:**  
* Adjust the pod’s `securityContext` (run as non‑root, drop `privileged: true`).  
* Grant the service account the needed PSP or update the namespace’s pod security level.  

---

### 8. NetworkPolicy blocking required traffic (e.g., to the registry)  
**Why:**  
* A `NetworkPolicy` denies egress from the pod to the image registry or to required services, causing pull or runtime failures.  

**Verify:**  
```bash
kubectl get networkpolicy -n kubexplain-eval
kubectl describe networkpolicy <policy-name> -n kubexplain-eval
```
* Check whether egress rules allow traffic to the registry’s IP/port.  

**Fix:**  
* Add an egress rule that permits traffic to the registry (`to: { ipBlock: { cidr: <registry‑cidr> } }`).  

---

### 9. ServiceAccount token / RBAC issue  
**Why:**  
* The pod uses a ServiceAccount that lacks permission to pull images from a private registry (e.g., via `imagePullSecrets` managed by a controller) or to mount certain secrets.  

**Verify:**  
```bash
kubectl get sa -n kubexplain-eval
kubectl describe sa <serviceaccount> -n kubexplain-eval
kubectl auth can-i get secret --as=system:serviceaccount:kubexplain-eval:<serviceaccount>
```
* Errors in the pod description like “serviceaccount token not found”.  

**Fix:**  
* Grant the ServiceAccount the needed RBAC role, or switch the pod to a ServiceAccount that already has the rights.  

---

## How to proceed when you regain cluster access  

1. **Run `kubectl describe pod s02-imagepull -n kubexplain-eval` first.**  
   * The `Events` section usually tells you the exact failure (image pull, scheduling, init‑container, etc.).  

2. **Check the pod’s current phase (`kubectl get pod … -o wide`).**  
   * `Pending` → scheduling/resource issues.  
   * `Running` but immediately `CrashLoopBackOff` → container runtime error.  
   * `ImagePullBackOff` → registry/auth problems.  

3. **Inspect logs if the container has started at least once.**  
   * `kubectl logs …` for the main container and any init containers.  

4. **Iterate through the list above, fixing the first cause you confirm.**  
   * After each change, delete the pod (`kubectl delete pod …`) so a fresh pod is created with the updated spec.  

---

### Quick checklist (copy‑paste when you’re back)

```bash
# 1. Basic status & events
kubectl describe pod s02-imagepull -n kubexplain-eval

# 2. Image‑pull diagnostics
kubectl get pod s02-imagepull -n kubexplain-eval -o jsonpath='{.status.containerStatuses[*].state}'
kubectl logs s02-imagepull -n kubexplain-eval --previous   # if container started once

# 3. Resource / scheduling
kubectl get nodes -o wide
kubectl describe quota -n kubexplain-eval

# 4. Init‑container logs (if any)
kubectl logs s02-imagepull -n kubexplain-eval -c <init‑container-name>

# 5. Security / RBAC
kubectl get psp
kubectl auth can-i get secret --as=system:serviceaccount:kubexplain-eval:<sa-name>
```

Apply the appropriate fix from the sections above, then recreate the pod.  In most clusters the **single most common blocker** for a pod that never reaches `Running` is an **image‑pull failure**, so start there if you’re short on time.
