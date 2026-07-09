# s05 | mode=none | run=1 | 13.2s | needs_search=0

**Q:** My pod 's05-pending' in namespace 'kubexplain-eval' is stuck in Pending state. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** no node satisfies the resource requests; lower requests or add capacity

---

## Most likely reasons a pod stays **Pending** (ordered by how often they cause the problem)

### 1. Not enough schedulable resources (CPU / memory) in the cluster  
**Why it happens** – The pod’s resource requests exceed what any node currently has free. The scheduler can’t find a node that satisfies the request, so the pod never leaves *Pending*.  

**How to verify (when you can run commands or view the UI)**  
- Look at the pod’s **Events** – you’ll see messages like *“0/5 nodes are available: 5 Insufficient cpu, 5 Insufficient memory”*.  
- Compare the pod’s `resources.requests` with the free capacity reported by `kubectl top nodes` or the node status page.  

**How to fix**  
- Reduce the pod’s CPU/Memory requests (or limits) if they are higher than needed.  
- Add more nodes or increase the size of existing nodes (e.g., larger instance types).  
- If you have a **Cluster Autoscaler** enabled, make sure it’s not disabled or throttled.  

---

### 2. Node selector / taints & tolerations mismatch  
**Why it happens** – The pod specifies a `nodeSelector`, `nodeAffinity`, or requires a toleration that no current node satisfies.  

**How to verify**  
- Check the pod spec for `nodeSelector`, `affinity`, or `tolerations`.  
- List the labels and taints on all nodes (`kubectl get nodes -o wide`).  
- Events will show *“0/5 nodes are available: 5 node(s) didn’t match node selector”* or *“5 node(s) had taints that the pod didn’t tolerate.”*  

**How to fix**  
- Add the missing label to a node, or adjust the pod’s selector/affinity to match existing node labels.  
- Add the required toleration to the pod, or remove the taint from the node if it’s not needed.  

---

### 3. PersistentVolumeClaim (PVC) not bound / storage class problems  
**Why it happens** – The pod references a PVC that is still **Pending** because no suitable PersistentVolume (PV) exists, or the storage class is mis‑configured.  

**How to verify**  
- Look at the PVC status (`kubectl get pvc`). If it’s *Pending*, the pod will stay pending.  
- Check events for messages like *“persistentvolumeclaim “my‑pvc” not found”* or *“no volume plugin found for claim”*.  

**How to fix**  
- Ensure a matching PV exists (size, access mode, storage class).  
- If using dynamic provisioning, verify the storage class is correct and the underlying provisioner is healthy.  
- Create or resize a PV, or adjust the PVC request to fit an existing PV.  

---

### 4. Image pull problems (registry auth, missing image, rate‑limit)  
**Why it happens** – The scheduler can place the pod, but the kubelet can’t start it because it can’t pull the container image. The pod stays in *Pending* with a *ContainerCreating* reason that appears as *Pending* in some UI views.  

**How to verify**  
- Events will contain messages such as *“Failed to pull image …: rpc error: …”* or *“ImagePullBackOff”*.  
- Check the pod’s `status.containerStatuses` for `waiting` with a `reason` like `ErrImagePull`.  

**How to fix**  
- Verify the image name and tag are correct.  
- Ensure the image exists in the registry and you have permission to pull it.  
- If a private registry is used, create or update the appropriate `imagePullSecret` and reference it in the pod spec.  
- Check for registry rate‑limits and consider using a pull secret with higher quota or a mirror.  

---

### 5. Namespace or ResourceQuota limits exceeded  
**Why it happens** – The namespace has a `ResourceQuota` that blocks additional CPU, memory, or object counts, so the scheduler rejects the pod.  

**How to verify**  
- Events will show *“exceeded quota: <quota‑name>”*.  
- Inspect the quota with `kubectl get quota` and compare used vs. hard limits.  

**How to fix**  
- Increase the quota limits (if you have permission).  
- Reduce the pod’s resource requests or delete other workloads to free quota.  

---

### 6. PodDisruptionBudget (PDB) or max‑surge constraints blocking scheduling  
**Why it happens** – A PDB may prevent new pods from being created if it would violate the `minAvailable` rule, especially during a rolling update.  

**How to verify**  
- Look for events mentioning *“cannot create pod because it would violate the pod’s disruption budget”*.  

**How to fix**  
- Adjust the PDB’s `minAvailable`/`maxUnavailable` values to be less restrictive.  
- Temporarily delete or modify the PDB while the pod is being created.  

---

### 7. NetworkPolicy or security policies preventing the pod from being scheduled  
**Why it happens** – Certain CNI plugins enforce policies that can block pod placement if required network resources are unavailable.  

**How to verify**  
- Events may include *“NetworkPolicy denies traffic to the pod”* or similar messages from the CNI.  

**How to fix**  
- Review the relevant `NetworkPolicy` objects and ensure they allow traffic to/from the pod’s namespace.  
- Adjust the policy or add an exception for the new pod.  

---

### 8. Cluster‑wide issues (API server overload, scheduler deadlock)  
**Why it happens** – Rare, but if the control‑plane components are unhealthy, scheduling may stall.  

**How to verify**  
- Check the health of the API server and scheduler (e.g., via the cluster’s monitoring dashboard).  
- Look for scheduler logs reporting errors or timeouts.  

**How to fix**  
- Restart the affected control‑plane components (or let the managed service recover).  
- Investigate underlying causes such as etcd performance or resource exhaustion on the control plane nodes.  

---

## How to systematically verify the root cause when you regain access

1. **Inspect pod events** – they usually contain the exact reason the scheduler rejected the pod.  
2. **Check node capacity vs. pod requests** – confirm there is at least one node that can satisfy the pod’s resources.  
3. **Review node labels, taints, and pod affinity/selector** – ensure they match.  
4. **Validate any PVCs** – make sure they are bound and the storage class works.  
5. **Look at image pull status** – confirm the image can be retrieved.  
6. **Examine namespace quotas and PDBs** – verify they are not blocking creation.  
7. **Confirm cluster health** – ensure the scheduler and API server are running normally.  

By walking through those steps in order, you’ll quickly pinpoint which of the above scenarios is causing `s05-pending` to remain in the **Pending** state and can apply the corresponding fix.
