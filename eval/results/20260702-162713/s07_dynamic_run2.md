# s07 | mode=dynamic | run=2 | 11.7s | needs_search=0

**Q:** My pod 's07-missing-cm' in namespace 'kubexplain-eval' fails to start its container. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** referenced ConfigMap does not exist; create it or fix the reference

---

## Most likely reasons the pod **s07‑missing‑cm** can’t start (ordered by probability)

### 1. Missing or unreadable ConfigMap  
**Why it’s likely** – The pod name itself hints that a ConfigMap is expected but not present. If a container mounts a ConfigMap that doesn’t exist, the pod will stay in *Waiting* with a `CreateContainerConfigError` reason.  

**How to verify (when you have access)**  
- Look at `kubectl describe pod s07-missing-cm -n kubexplain-eval`.  
- Check the **Events** section for messages such as *“configmap “<name>” not found”* or *“configmap volume … is missing”*.  
- Inspect the pod spec (`kubectl get pod … -o yaml`) for any `configMap` references (volumes, envFrom, env).  

**How to fix**  
- Create the missing ConfigMap with the expected name and data: `kubectl create configmap <name> --from-literal=…` (or apply a manifest).  
- If the ConfigMap exists in another namespace, either move it or add `namespace:` to the reference.  
- Ensure the ServiceAccount used by the pod has permission to read ConfigMaps (RBAC `get`, `list` on `configmaps`).

---

### 2. Image‑pull failure (wrong tag, private registry, missing credentials)  
**Why it’s likely** – A pod that can’t pull its image will stay in *Waiting* with reason `ErrImagePull` or `ImagePullBackOff`.  

**How to verify**  
- In the pod description, look for events like *“Failed to pull image …”* or *“Back-off pulling image …”*.  
- Check the container status line for `State: Waiting` and `Reason: ErrImagePull` / `ImagePullBackOff`.  

**How to fix**  
- Confirm the image name and tag are correct and that the image exists in the registry.  
- If the registry is private, create or update an `imagePullSecret` and reference it in the pod spec.  
- Verify the secret is in the same namespace and that the ServiceAccount can use it.  

---

### 3. Insufficient resources on any node (CPU / memory)  
**Why it’s likely** – If the pod requests more resources than any node can satisfy, the scheduler will not bind the pod, leaving it in *Pending* with a `FailedScheduling` event.  

**How to verify**  
- In `kubectl describe pod …`, check the **Events** for `FailedScheduling` messages that mention *“insufficient cpu”* or *“insufficient memory”*.  
- Look at the pod’s `resources.requests` and compare with node capacities (`kubectl get nodes -o wide`).  

**How to fix**  
- Reduce the resource requests/limits in the pod spec.  
- Add more nodes or increase node capacity.  
- Use a `HorizontalPodAutoscaler` or `Cluster Autoscaler` if appropriate.  

---

### 4. Node‑level constraints (taints, node selectors, affinity)  
**Why it’s likely** – A pod may be scheduled onto a node but then be rejected because the node is tainted or the pod’s selector/affinity doesn’t match any node.  

**How to verify**  
- Check the pod description for events like *“node(s) didn’t match pod affinity/anti‑affinity”* or *“node(s) had taints that the pod didn’t tolerate”*.  
- Review `nodeSelector`, `affinity`, and `tolerations` fields in the pod spec.  

**How to fix**  
- Adjust the pod’s `nodeSelector`/`affinity` to match available nodes, or add appropriate `tolerations`.  
- Remove or modify the taint on the node if it’s not required.  

---

### 5. CrashLoopBackOff due to container command or runtime error  
**Why it’s likely** – If the container starts, crashes, and restarts repeatedly, the pod will show `CrashLoopBackOff`.  

**How to verify**  
- In the pod description, see `State: Running` → `Last State: Terminated` with `Reason: Error` and a non‑zero exit code.  
- Look at the **Restart Count**; a high count indicates a loop.  

**How to fix**  
- Examine the container’s entrypoint/command for bugs.  
- Add proper liveness/readiness probes if they are causing premature restarts.  
- Use an init container or a sidecar with debugging tools to run the command manually and see the error output.  

---

### 6. Init container failure  
**Why it’s likely** – If an init container fails, the main container never starts and the pod stays in *Init:Error*.  

**How to verify**  
- In `kubectl describe pod …`, look at the **Init Containers** section for a non‑zero exit code or `Reason: Error`.  

**How to fix**  
- Check the init container’s image, command, and any ConfigMap/Secret it uses.  
- Fix the underlying issue (e.g., missing file, permission problem) and redeploy.  

---

### 7. Security context / permission problems (PodSecurityPolicy, SELinux, AppArmor)  
**Why it’s likely** – A pod may be rejected by the API server or kubelet if it violates security policies.  

**How to verify**  
- Look for events mentioning *“failed to create pod sandbox”* or *“security context denied”*.  
- Check the pod’s `securityContext` and any `PodSecurityPolicy` that applies.  

**How to fix**  
- Adjust the pod’s security settings (runAsUser, privileged flag, capabilities) to comply with the cluster’s policies.  
- Update the PSP or move the pod to a namespace with a more permissive policy.  

---

## How to verify each cause (once you regain access)

1. **Describe the pod** – `kubectl describe pod s07-missing-cm -n kubexplain-eval` gives events, container states, and reasons.  
2. **Inspect the pod spec** – `kubectl get pod s07-missing-cm -n kubexplain-eval -o yaml` shows ConfigMap references, resources, image, node selectors, security context, etc.  
3. **Check related resources** –  
   - ConfigMaps: `kubectl get cm -n kubexplain-eval`  
   - ImagePullSecrets: `kubectl get secret -n kubexplain-eval`  
   - Nodes: `kubectl get nodes` and `kubectl describe node <node>` for taints and capacity.  
4. **Review controller logs** – If the pod is managed by a Deployment/StatefulSet, look at the controller’s events (`kubectl describe deployment …`).  

By following the verification steps in the order above, you’ll quickly pinpoint which of these common failure modes is responsible for the pod not starting, and then apply the corresponding fix.
