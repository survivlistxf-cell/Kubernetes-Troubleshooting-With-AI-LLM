# s05 | mode=dynamic | run=3 | 12.5s | needs_search=0

**Q:** My pod 's05-pending' in namespace 'kubexplain-eval' is stuck in Pending state. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** no node satisfies the resource requests; lower requests or add capacity

---

### Most likely reasons a pod stays **Pending** (ordered by how often they happen)

| # | Reason (why the scheduler can’t place the pod) | How you would verify it (what to look for) | Typical fix |
|---|-----------------------------------------------|--------------------------------------------|-------------|
| 1 | **Not enough free CPU / memory on any node** (the pod’s *requests* exceed the sum of allocatable resources that are still unused) | In the pod’s `Events` you will see messages such as `FailedScheduling – pod … failed to fit in any node` and a line that says *“Node didn’t have enough resource: CPU, requested: X, used: Y, capacity: Z”*. The same wording appears in the documentation for pending pods. | Reduce the pod’s resource **requests** (or limits) or free up resources on the cluster (delete other pods, scale down workloads). If the cluster is expected to grow, add more worker nodes or enable the cluster‑autoscaler. |
| 2 | **Node selector / node affinity / pod affinity‑/anti‑affinity rules that don’t match any node** | The `Events` will contain a reason like *“node(s) didn’t match node selector”* or *“no nodes are available that match pod affinity”*. You can also see the selector/affinity fields in the pod spec and compare them with the labels on the nodes (`kubectl get nodes --show-labels`). | Adjust the selector/affinity so that at least one node satisfies it, or add the required labels to a node. If the rules are unnecessary, remove them. |
| 3 | **Taints on all nodes without a matching toleration** | Scheduler events will say *“node(s) had taints … that the pod didn’t tolerate”*. The pod spec will have no `tolerations` entry for the taint key/value. | Add an appropriate `toleration` to the pod (or to the workload controller that creates it) or remove the taint from the nodes if it isn’t needed. |
| 4 | **HostPort conflict** – the pod asks for a specific hostPort and every node already has that port bound by another pod | The event will read *“hostPort … is already allocated on node …”* or *“pod … failed to fit because of hostPort conflict”*. | Either remove the `hostPort` usage (prefer a Service → ClusterIP) or limit the number of such pods to the number of nodes, or schedule them onto nodes that still have the port free. |
| 5 | **PersistentVolumeClaim is still pending** (the pod references a PVC that hasn’t been bound) | In the pod description you’ll see the volume state as *“Pending”* and an event like *“persistentvolumeclaim … not bound”*. | Ensure a matching `PersistentVolume` exists (or that a dynamic provisioner can create one). Fix the PVC’s storage class, size, or access mode, or create a suitable PV manually. |
| 6 | **Explicit `nodeName` set to a node that is not present or not Ready** | The pod spec contains `nodeName: <some‑node>` and the scheduler will not try to place it elsewhere. The event will say *“node <name> not found”* or *“node <name> is NotReady”*. | Remove the `nodeName` field (let the scheduler decide) or correct it to an existing Ready node. |
| 7 | **Cluster‑autoscaler disabled and the cluster truly has no capacity** (e.g., all nodes are at 100 % CPU/memory) | Same messages as #1, but you’ll also notice that the cluster has no spare capacity even after checking all nodes. | Enable or configure the autoscaler, or manually add more nodes. |

---

### How you would **verify** each cause (when you regain access)

1. **Describe the pod** – `kubectl describe pod s05-pending -n kubexplain-eval`.  
   *Look at the **Events** section*; the scheduler’s reason strings are the quickest clues.

2. **Check node resources** – list nodes with their allocatable CPU/memory and current usage.  
   If every node shows high `cpu`/`memory` usage, the pod is likely blocked by resource limits.

3. **Inspect node labels & taints** – compare the pod’s `nodeSelector`, `affinity`, and `tolerations` with the labels/taints on each node.

4. **Search for hostPort usage** – see if any other pod on the cluster already binds the same hostPort.

5. **Look at PVC status** – `kubectl get pvc -n kubexplain-eval` and `kubectl describe pvc <name>` to see if it’s still *Pending*.

6. **Validate `nodeName`** – if present, ensure that node exists (`kubectl get nodes`) and is `Ready`.

7. **Cluster‑autoscaler** – check the autoscaler deployment or cloud‑provider node‑group settings to confirm it’s active.

---

### Typical **fixes** you would apply (once you can run commands)

| Cause | Fix (what you would change) |
|-------|-----------------------------|
| Insufficient resources | Lower `resources.requests` in the pod spec, or delete/scale down other workloads, or add more nodes. |
| Selector / affinity mismatch | Edit the pod (or its controller) to use a selector that matches at least one node, or add the missing label to a node. |
| Missing toleration | Add a `tolerations` entry that matches the node taint, or remove the taint from the nodes. |
| HostPort conflict | Remove the `hostPort` field, or limit the number of such pods, or schedule onto a node where the port is free. |
| PVC pending | Create a matching PV, adjust the storage class, or fix size/AccessModes so the claim can bind. |
| Wrong `nodeName` | Delete or correct the `nodeName` field so the scheduler can place the pod. |
| No capacity & autoscaler off | Enable the autoscaler or manually provision additional nodes. |

---

### Quick checklist you can run when you get back into the cluster

1. `kubectl describe pod s05-pending -n kubexplain-eval` → read **Events**.  
2. If you see *FailedScheduling* with a resource‑shortage message → look at node usage.  
3. If you see *node(s) didn’t match node selector* / *taints* / *affinity* → compare pod spec vs node labels/taints.  
4. If you see *hostPort* conflict → locate the other pod using that port.  
5. If you see *persistentvolumeclaim … not bound* → inspect the PVC.  
6. If you see *node <name> not found* → correct or remove `nodeName`.  

Address the first matching issue you find, then re‑apply (or let the controller recreate) the pod and it should transition to **Running**.
