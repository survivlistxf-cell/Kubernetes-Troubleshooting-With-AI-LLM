# s05 | mode=static | run=3 | 11.1s | needs_search=0

**Q:** My pod 's05-pending' in namespace 'kubexplain-eval' is stuck in Pending state. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** no node satisfies the resource requests; lower requests or add capacity

---

## Most likely reasons a pod stays **Pending** (in order of probability)

### 1. Not enough CPU / memory on any node  
**Why it happens** – The pod’s `resources.requests` (or its default request) exceed the free capacity of every node. The scheduler will refuse to place the pod and you’ll see a *FailedScheduling* event that mentions “did not have enough resource: cpu/memory”.  

**How to verify** – Look at the pod’s events (the scheduler’s messages). They will list “insufficient cpu” or “insufficient memory” together with the amount requested and the amount already used on each node. You can also compare the pod’s request values with the node‑level `allocatable` resources shown in `kubectl describe node`.  

**How to fix**  
- Reduce the pod’s CPU/memory **requests** (or limits, if they are also being used for scheduling).  
- Scale the workload down (fewer replicas) so the total demand fits.  
- Add more worker nodes or increase the size of existing nodes (e.g., larger VM types).  

---

### 2. Node selector / node affinity constraints that no node satisfies  
**Why it happens** – The pod spec contains a `nodeSelector`, `nodeAffinity`, or a hard `nodeName` that points to a label or node that does not exist or is not present on any ready node.  

**How to verify** – The scheduler events will contain a line such as “no nodes match node selector” or “node(s) didn’t match pod affinity”. Inspect the pod spec for `nodeSelector`, `affinity`, or `nodeName` fields and compare them with the labels on your nodes (`kubectl get nodes --show-labels`).  

**How to fix**  
- Correct the selector/affinity rules so they match at least one node.  
- Add the required label to a node, or remove the selector if it isn’t needed.  
- If a specific `nodeName` was set by mistake, delete it or let the scheduler choose.  

---

### 3. Taints on nodes without matching tolerations  
**Why it happens** – One or more nodes are tainted (e.g., `key=value:NoSchedule`) and the pod does not declare a toleration for that taint. The scheduler therefore skips those nodes.  

**How to verify** – Scheduler events will say “node(s) had taints that the pod didn’t tolerate”. List the taints on each node (`kubectl describe node`) and compare them with the pod’s `tolerations` section.  

**How to fix**  
- Add an appropriate toleration to the pod spec.  
- Remove or change the taint on the node if it isn’t required.  

---

### 4. HostPort or HostNetwork usage limiting placement  
**Why it happens** – The pod requests a specific `hostPort` (or uses `hostNetwork`). Only one pod can bind to a given hostPort on a node, so the scheduler can place the pod only on nodes where that port is free. If every node already has a pod using that port, the new pod stays pending.  

**How to verify** – Scheduler events will contain “hostPort … is already allocated”. Check the pod spec for `hostPort` or `hostNetwork: true`.  

**How to fix**  
- Remove the `hostPort` requirement if it isn’t essential, or change it to a different port.  
- Use a Service (ClusterIP/NodePort) instead of hostPort for exposing the pod.  
- If hostPort is required, add more nodes so there is at least one free port.  

---

### 5. PersistentVolumeClaim with `WaitForFirstConsumer` that can’t bind  
**Why it happens** – The pod uses a PVC that is set to `volumeBindingMode: WaitForFirstConsumer`. The scheduler must find a node that satisfies both the pod’s constraints **and** the storage class’s topology (e.g., a specific zone). If no node meets both, the pod remains pending.  

**How to verify** – Events will mention “persistentvolumeclaim … is not bound” or “no nodes are available for volume binding”. Look at the PVC’s status and the storage class’s `allowedTopologies`.  

**How to fix**  
- Adjust the PVC or storage class topology to include a node that can host the pod.  
- Remove `WaitForFirstConsumer` if immediate binding is acceptable.  
- Add a node in the required zone/region.  

---

### 6. Nodes are NotReady or unschedulable (e.g., cordoned)  
**Why it happens** – All nodes are either marked `NotReady` (kubelet down, network partition) or have been manually cordoned (`kubectl cordon`). The scheduler has nowhere to place the pod.  

**How to verify** – Scheduler events may say “no nodes are available”. Checking node conditions will show `Ready=False` or `Spec.Unschedulable=True`.  

**How to fix**  
- Uncordon the node (`kubectl uncordon <node>`).  
- Resolve the underlying node health issue (restart kubelet, fix networking, etc.).  
- Add healthy nodes to the cluster.  

---

## Quick verification checklist (when you regain access)

1. **Inspect pod events** – they give the exact reason the scheduler rejected each node.  
2. **Compare pod resource requests** with node allocatable resources.  
3. **Review node selectors / affinity** against node labels.  
4. **Check node taints** and pod tolerations.  
5. **Look for hostPort / hostNetwork** usage.  
6. **Examine PVC status** and storage class topology.  
7. **Confirm node health** (Ready state, unschedulable flag).  

Address the first item that matches the observed event, then re‑apply the corrected pod spec or adjust cluster capacity. Once the underlying constraint is removed, the scheduler will immediately bind the pod and it will transition from **Pending** to **Running**.
