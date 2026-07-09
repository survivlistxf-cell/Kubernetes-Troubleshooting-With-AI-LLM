# s05 | mode=static | run=1 | 14.4s | needs_search=0

**Q:** My pod 's05-pending' in namespace 'kubexplain-eval' is stuck in Pending state. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** no node satisfies the resource requests; lower requests or add capacity

---

**Why `s05‑pending` stays in **Pending**  
A pod that never leaves the *Pending* phase has not been scheduled onto any node. The scheduler has rejected it for one (or more) of the reasons below. The list is ordered from the most common cause to the least common, together with the quick checks you can perform (once you regain cluster access) and the typical remediation steps.

---

## 1. Not enough CPU / memory on any node  
**Probability ≈ 70 %** – most clusters run close to capacity and a pod that asks for more resources than any node can provide is rejected immediately.

### How to verify  
* Look at the pod’s **resource requests** (CPU, memory) in its spec.  
* Compare them with the **allocatable resources** of each node (the scheduler prints a “fit failure” message that shows “Node didn’t have enough resource: CPU, requested: X, used: Y, capacity: Z”).  
* If you have many pods with similar requests, check the cluster’s overall utilisation (e.g. via the metrics server or a monitoring dashboard).

### How to fix  
* **Lower the pod’s requests/limits** to a value that fits on at least one node.  
* **Add capacity** – provision additional worker nodes or increase the size of existing nodes.  
* **Scale down** other workloads that are over‑provisioned, or enable **cluster autoscaling** so new nodes are created automatically when needed.

---

## 2. Node‑selector / node‑affinity mismatch  
**Probability ≈ 15 %** – a pod may request a specific label (e.g. `disktype: ssd`) that no current node carries, or its affinity rules are too restrictive.

### How to verify  
* Inspect the pod’s `nodeSelector`, `affinity.nodeAffinity.requiredDuringSchedulingIgnoredDuringExecution`, and `affinity.podAffinity` sections.  
* List the labels on all nodes and see whether any node satisfies the selector/affinity expression.  
* Scheduler events will contain a line like “no nodes match node selector”.

### How to fix  
* **Adjust the selector/affinity** to match an existing node label, or add the required label to a node.  
* If the constraint is intentional, **add a new node** that carries the needed label (e.g. a node with an SSD).  

---

## 3. Taints on nodes without matching tolerations  
**Probability ≈ 10 %** – nodes can be tainted (e.g. `key=dedicated:NoSchedule`) to repel pods that don’t explicitly tolerate them. If every node has a taint that the pod doesn’t tolerate, the scheduler will reject it.

### How to verify  
* Check the pod’s `tolerations` list.  
* List the taints on each node (`kubectl describe node`).  
* Scheduler events will show “node(s) had taints that the pod didn’t tolerate”.

### How to fix  
* **Add the appropriate toleration** to the pod spec.  
* **Remove or modify the taint** on the node(s) if the restriction is no longer needed.  
* **Create a dedicated node pool** for the taint and schedule the pod there.

---

## 4. HostPort / HostNetwork conflicts  
**Probability ≈ 5 %** – a pod that binds a `hostPort` (or uses `hostNetwork`) can only be placed on a node where that port is free. If every node already has the port in use, the pod stays pending.

### How to verify  
* Look for a `hostPort` entry (or `hostNetwork: true`) in the container spec.  
* Scheduler events will mention “hostPort … is already allocated”.  
* Check which pods are already using the same hostPort on each node.

### How to fix  
* **Remove the hostPort** if it isn’t required, or change it to a different port.  
* **Add more nodes** so that the port can be allocated on a free node.  
* **Use a Service** (ClusterIP/NodePort) instead of hostPort for exposing the application.

---

## 5. PersistentVolumeClaim still pending (unbound)  
**Probability ≈ 3 %** – if the pod references a PVC that cannot be bound (e.g., no matching StorageClass, insufficient storage, or the underlying volume is still being provisioned), the scheduler will wait.

### How to verify  
* Examine the PVC status (`Bound` vs `Pending`).  
* Scheduler events will contain “persistentvolumeclaim … is not bound”.  
* Check the storage class provisioner logs if dynamic provisioning is used.

### How to fix  
* **Correct the PVC spec** (size, storage class, access mode).  
* **Create or fix the StorageClass** so that a volume can be provisioned.  
* **Manually provision** a PV that satisfies the claim, then bind it.  

---

## 6. No ready nodes in the cluster (cluster‑wide outage)  
**Probability ≈ 2 %** – all nodes may be `NotReady` (network partition, kubelet crash, etc.), leaving the scheduler with nowhere to place pods.

### How to verify  
* Check node conditions (`Ready` vs `NotReady`).  
* Scheduler events will show “no nodes are available”.  
* Look at the kubelet logs on the nodes for errors.

### How to fix  
* **Restore node health** – restart the kubelet, fix network issues, or reboot the node.  
* **Replace failed nodes** with new worker machines.  
* If the control plane is also affected, bring the API server / etcd back online.

---

## 7. Custom scheduler name that does not exist  
**Probability ≈ 1 %** – the pod may specify `schedulerName: my‑scheduler` but that scheduler is not deployed or is mis‑configured.

### How to verify  
* Look at the pod’s `schedulerName` field.  
* Scheduler events will say “no scheduler named … found”.

### How to fix  
* **Deploy the missing scheduler** (or correct its name).  
* **Remove the custom schedulerName** to fall back to the default scheduler.

---

### Quick “first‑check” workflow (once you can run commands)

1. **Describe the pod** – the `Events` section usually tells you the exact reason (e.g., “FailedScheduling – pod … failed to fit in any node”).  
2. **Inspect node resources** – see if any node has enough free CPU/memory.  
3. **Review selectors, taints, and hostPort** – compare pod spec against node labels/taints.  
4. **Check PVC status** – ensure any volume claims are bound.  
5. **Confirm node health** – all nodes should be `Ready`.  

By following the order above, you’ll typically pinpoint the root cause in a few minutes and can apply the corresponding remediation. If the first cause (resource shortage) is not the issue, move down the list until the scheduler’s event messages stop complaining. Once the underlying constraint is removed or adjusted, the pod will transition from *Pending* to *Running* automatically.
