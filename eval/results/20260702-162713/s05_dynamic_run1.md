# s05 | mode=dynamic | run=1 | 9.3s | needs_search=0

**Q:** My pod 's05-pending' in namespace 'kubexplain-eval' is stuck in Pending state. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** no node satisfies the resource requests; lower requests or add capacity

---

## Most likely reasons a pod stays **Pending** (ordered by probability)

### 1. Not enough compute resources (CPU / memory) on any node  
**Why it happens** – The pod’s `resources.requests` are higher than the free capacity on every node (or the cluster is out of allocatable resources).  
**How to verify**  
- `kubectl describe pod s05-pending -n kubexplain-eval` → look at the **Events** section for messages like *“FailedScheduling – pod … failed to fit in any node”* and *“Node didn’t have enough resource: CPU, requested: X, used: Y, capacity: Z”*.  
- `kubectl get nodes -o wide` and compare each node’s `allocatable` vs `capacity` (or `kubectl top nodes` if metrics‑server is installed).  

**How to fix**  
- Reduce the pod’s resource **requests** (or limits) in its manifest.  
- Scale down other workloads that are consuming the resources.  
- Add more nodes or increase node size (e.g., larger VM types).  

---

### 2. Node selector / taints & tolerations mismatch  
**Why it happens** – The pod specifies a `nodeSelector`, `nodeAffinity`, or requires a toleration that no current node satisfies.  
**How to verify**  
- In the pod description, check the **Node-Selectors**, **Affinity**, and **Tolerations** fields.  
- Run `kubectl get nodes -o jsonpath='{range .items[*]}{.metadata.name} {.spec.taints}{"\n"}{end}'` to see which taints exist on nodes.  

**How to fix**  
- Adjust the pod’s selector/affinity to match an existing node label.  
- Add the required **toleration** to the pod spec, or remove the taint from the node (if appropriate).  

---

### 3. PersistentVolumeClaim (PVC) not bound  
**Why it happens** – The pod references a PVC that is still in **Pending** because no suitable PV exists or the storage class cannot provision a volume.  
**How to verify**  
- `kubectl get pvc -n kubexplain-eval` → look for PVCs with status **Pending**.  
- `kubectl describe pvc <pvc-name> -n kubexplain-eval` for events about provisioning failures.  

**How to fix**  
- Create a matching PV manually, or adjust the PVC’s `storageClassName`/size to match available storage.  
- Ensure the storage class has a provisioner that can create volumes (e.g., CSI driver is running).  

---

### 4. Pod affinity / anti‑affinity rules prevent placement  
**Why it happens** – The pod declares `podAffinity` or `podAntiAffinity` that cannot be satisfied given the current pod distribution.  
**How to verify**  
- Look at the **Affinity** section of `kubectl describe pod …`.  
- Check existing pods that match the affinity selector and see if they are on nodes that would satisfy the rule.  

**How to fix**  
- Relax or remove the affinity rules, or add/modify other pods so the rule can be satisfied.  

---

### 5. HostPort or HostNetwork conflicts  
**Why it happens** – The pod requests a specific `hostPort` (or uses `hostNetwork`) that is already taken on every node.  
**How to verify**  
- In the pod spec, see any `hostPort` values under `containers[].ports`.  
- Run `kubectl get pods -A -o wide | grep <hostPort>` to see if another pod already occupies that port.  

**How to fix**  
- Remove the `hostPort` requirement (use a Service instead).  
- Change the port number to one that is free, or add more nodes.  

---

### 6. Missing or unsatisfied ImagePullSecrets / registry access  
**Why it happens** – The scheduler can place the pod, but the kubelet cannot pull the container image, causing the pod to stay in **Pending** (rare; usually it moves to **ImagePullBackOff**).  
**How to verify**  
- Check the pod events for messages like *“Failed to pull image …”* or *“ErrImagePull”*.  

**How to fix**  
- Ensure the correct `imagePullSecrets` are defined and the secret contains valid credentials.  
- Verify the image name and tag are correct and the registry is reachable.  

---

## Quick verification checklist (run when you regain access)

```bash
# 1. General pod status & events
kubectl describe pod s05-pending -n kubexplain-eval

# 2. Resource pressure on nodes
kubectl top nodes          # (requires metrics‑server)
kubectl get nodes -o yaml | grep -A5 allocatable

# 3. PVC health (if the pod uses volumes)
kubectl get pvc -n kubexplain-eval
kubectl describe pvc <pvc-name> -n kubexplain-eval

# 4. Node selector / taints
kubectl get nodes -L <label-key>
kubectl describe node <node-name> | grep -i taint
```

Run the relevant commands, locate the specific event that explains why the scheduler rejected the pod, and apply the corresponding fix from the list above. Once the underlying issue is resolved, the pod should transition from **Pending** to **Running**.
