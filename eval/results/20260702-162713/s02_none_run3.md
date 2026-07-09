# s02 | mode=none | run=3 | 13.0s | needs_search=0

**Q:** My pod 's02-imagepull' in namespace 'kubexplain-eval' will not start. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** image cannot be pulled (bad name/tag or missing credentials); fix image reference

---

## Most likely reasons the pod **s02‑imagepull** cannot start  
(ordered from the problems that occur most often to the least common)

### 1. Image pull failure (ImagePullBackOff / ErrImagePull)  
**Why it happens**  
- Wrong image name, tag or digest.  
- The registry requires authentication and the pod has no valid `imagePullSecret`.  
- The node cannot reach the registry (network/DNS issue, proxy, firewall).  

**How to verify**  
- Look at the pod’s **Events** (`kubectl describe pod s02-imagepull -n kubexplain-eval`).  
- The event will show `Failed to pull image` or `Back-off pulling image`.  
- Check the container status (`kubectl get pod s02-imagepull -n kubexplain-eval -o jsonpath='{.status.containerStatuses[0].state.waiting.reason}'`).  

**How to fix**  
- Correct the image reference (registry URL, repository, tag/digest).  
- If the registry is private, create a secret with the credentials and reference it in the pod spec (`imagePullSecrets`).  
- Verify node network connectivity to the registry (DNS resolution, outbound HTTP/HTTPS).  

---

### 2. Scheduling / node‑selection problems  
**Why it happens**  
- No node matches the pod’s `nodeSelector`, `nodeAffinity`, or required `taints`/`tolerations`.  
- The cluster is out of resources (CPU, memory, pod count) on all nodes.  

**How to verify**  
- `kubectl describe pod s02-imagepull -n kubexplain-eval` – look for events like `0/3 nodes are available: 3 Insufficient cpu, 3 node(s) had taints`.  
- `kubectl get nodes` and check the **Allocatable** resources vs. **Requested** resources (`kubectl describe node <node>`).  

**How to fix**  
- Adjust or remove the `nodeSelector`/`affinity`/`tolerations` in the pod spec.  
- Increase cluster capacity (add nodes or enlarge existing ones).  
- If a specific taint is blocking the pod, either add a matching toleration or remove the taint from the node.  

---

### 3. Resource limits / requests too high  
**Why it happens**  
- The pod requests more CPU or memory than any node can provide.  
- The pod’s `limits` exceed the node’s allocatable resources, causing the scheduler to reject it.  

**How to verify**  
- In the pod description, check the **Requests** and **Limits** fields.  
- Compare them with node capacity (`kubectl describe node <node>`).  

**How to fix**  
- Lower the requested resources or remove unnecessary limits.  
- If the workload truly needs more resources, scale the node pool or enable cluster autoscaling.  

---

### 4. Init container failure  
**Why it happens**  
- An init container exits with a non‑zero status, preventing the main container from starting.  
- The init container suffers from the same image‑pull or resource problems as above.  

**How to verify**  
- In `kubectl describe pod …` look for `Init:CrashLoopBackOff` or `Init:Error`.  
- Examine the init container’s logs (`kubectl logs s02-imagepull -n kubexplain-eval -c <init‑container-name>`).  

**How to fix**  
- Fix the underlying issue (image name, secret, resources) for the init container.  
- If the init step is no longer needed, remove it from the pod spec.  

---

### 5. PersistentVolumeClaim (PVC) binding / mount problems  
**Why it happens**  
- The pod references a PVC that is still `Pending` or bound to a volume that cannot be attached.  
- The storage class is mis‑configured or the underlying storage is out of capacity.  

**How to verify**  
- `kubectl get pvc -n kubexplain-eval` – check the `STATUS` column.  
- In the pod description, look for events like `FailedMount` or `MountVolume.SetUp` errors.  

**How to fix**  
- Ensure the PVC’s storage class exists and has available capacity.  
- Delete and recreate the PVC if it is stuck, or adjust its size/parameters.  

---

### 6. NetworkPolicy or security restrictions  
**Why it happens**  
- A `NetworkPolicy` blocks egress to the image registry or required services.  
- Pod security policies (or the newer `PodSecurity` standards) deny required capabilities (e.g., privileged mode, hostPath).  

**How to verify**  
- List NetworkPolicies in the namespace (`kubectl get netpol -n kubexplain-eval`).  
- Check the pod’s `SecurityContext` and any `PodSecurity` admission messages in the pod events.  

**How to fix**  
- Update or add a NetworkPolicy that allows egress to the registry.  
- Adjust the pod’s security context to meet the cluster’s policy (add required capabilities, allow privileged mode, etc.).  

---

### 7. Namespace‑level quota or limitrange violations  
**Why it happens**  
- The namespace has a `ResourceQuota` that is already exhausted (pods, CPU, memory, storage).  
- A `LimitRange` forces default requests/limits that the pod does not satisfy.  

**How to verify**  
- `kubectl get quota -n kubexplain-eval` and `kubectl describe quota <quota-name>`.  
- `kubectl get limitrange -n kubexplain-eval`.  

**How to fix**  
- Increase the quota (if you have permission) or delete unused resources.  
- Adjust the pod’s resource requests/limits to fit within the defined `LimitRange`.  

---

### 8. Node‑level problems (disk pressure, network, kubelet)  
**Why it happens**  
- The node where the pod is scheduled reports `DiskPressure`, `MemoryPressure`, or `NetworkUnavailable`.  
- The kubelet on the node is unhealthy, causing pods to stay in `Pending` or `ContainerCreating`.  

**How to verify**  
- `kubectl describe node <node>` – look for `Conditions` with `Status: True` for `DiskPressure`, `MemoryPressure`, etc.  
- Check node logs or the cluster’s monitoring system for kubelet errors.  

**How to fix**  
- Free up disk space or memory on the node, or cordon/drain the node and let the pod be scheduled elsewhere.  
- Restart the kubelet service on the affected node (requires node‑level access).  

---

## How to use this checklist when you regain access  

1. **Run `kubectl describe pod s02-imagepull -n kubexplain-eval`** – the **Events** section will usually point directly to the root cause (image pull, scheduling, quota, etc.).  
2. Follow the verification steps for the first matching cause in the list above.  
3. Apply the corresponding fix, then delete the pod (`kubectl delete pod …`) so it can be recreated with the corrected configuration.  

If the first cause you check does not explain the failure, move down the list until you find a matching symptom. Most pods that never start are blocked by image‑pull or scheduling issues, so those are the best places to start. Good luck!
