# s05 | mode=static | run=2 | 12.2s | needs_search=0

**Q:** My pod 's05-pending' in namespace 'kubexplain-eval' is stuck in Pending state. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** no node satisfies the resource requests; lower requests or add capacity

---

## Most likely reasons a pod stays **Pending** (ordered by how often they occur)

1. **Insufficient cluster resources (CPU / memory)**
   * **Why it happens** – The pod’s `requests` (or limits) exceed the amount of free CPU or memory on every node. The scheduler can’t find a node that can satisfy the request, so it keeps the pod in *Pending* and records a *FailedScheduling* event.
   * **How to verify** – Look at the pod’s events (the scheduler will list a message such as “failed to fit in any node” and show the exact resource that is lacking). Then inspect the node‑level resource usage (e.g., `kubectl top nodes` or the node’s `allocatable` vs. `capacity` fields) to see whether the requested amount is available anywhere.
   * **How to fix**  
     * Reduce the pod’s CPU/memory `requests` (or remove unnecessary limits).  
     * Scale down other workloads that are consuming the scarce resources.  
     * Add more nodes or increase the size of existing nodes (e.g., larger VM types).  

2. **Node selector / affinity rules that don’t match any node**
   * **Why it happens** – The pod spec contains a `nodeSelector`, `nodeAffinity`, or `podAffinity/AntiAffinity` that only matches a subset of nodes, and none of those nodes are currently Ready or have enough resources.
   * **How to verify** – Examine the pod’s spec for `nodeSelector` or `affinity` sections and compare the required labels/expressions with the labels on the nodes (`kubectl get nodes --show-labels`). The scheduler event will usually say “no nodes match node selector/affinity”.
   * **How to fix**  
     * Adjust or remove the selector/affinity so that it matches at least one Ready node.  
     * Add the required label(s) to an appropriate node.  
     * If the rule is intentional, ensure a node with the needed labels exists and is Ready.

3. **Taints on all nodes without matching tolerations**
   * **Why it happens** – Nodes may be tainted (e.g., `node.kubernetes.io/not-ready`, `node.kubernetes.io/unreachable`, or custom taints) and the pod does not declare a toleration for those taints. The scheduler therefore refuses to place the pod.
   * **How to verify** – Check the pod’s `tolerations` section and the list of taints on each node (`kubectl describe node`). Scheduler events will mention “node(s) had taints that the pod didn’t tolerate”.
   * **How to fix**  
     * Add appropriate tolerations to the pod spec.  
     * Remove or modify the taints on the nodes if they are no longer needed.  

4. **PersistentVolumeClaim (PVC) binding is still pending**
   * **Why it happens** – If the pod uses a PVC and the underlying PersistentVolume (PV) cannot be provisioned or bound (e.g., no matching storage class, insufficient storage, or the provisioner is down), the pod will stay Pending until the claim is satisfied.
   * **How to verify** – Look at the pod’s events for a message like “persistentvolumeclaim ‘my-pvc’ not bound”. Then inspect the PVC’s status (`kubectl get pvc`) and any associated events on the PVC or StorageClass.
   * **How to fix**  
     * Ensure a suitable StorageClass exists and is functional.  
     * Adjust the PVC size or access mode to match an available PV.  
     * If using dynamic provisioning, verify that the provisioner (e.g., CSI driver) is healthy.  

5. **All nodes are NotReady or unreachable**
   * **Why it happens** – The cluster may have lost connectivity to its worker nodes (network partition, kubelet crash, node maintenance). Even if the pod’s resource request is modest, the scheduler cannot place it because no node is considered Ready.
   * **How to verify** – Check node conditions (`kubectl get nodes`) – look for `Ready=False` or `Unknown`. Scheduler events may say “no nodes are available”.
   * **How to fix**  
     * Investigate node health (e.g., restart kubelet, fix network issues, bring the node back online).  
     * If nodes are permanently down, replace them or scale the cluster up.  

6. **HostPort or HostNetwork constraints**
   * **Why it happens** – The pod requests a specific `hostPort` (or uses `hostNetwork`) that can only be bound once per node. If every node already has a pod using that port, the scheduler cannot find a free spot.
   * **How to verify** – Review the pod spec for `hostPort` or `hostNetwork`. Scheduler events may mention “hostPort … is already allocated”.
   * **How to fix**  
     * Remove the hostPort requirement if it isn’t essential, or change it to a different port.  
     * Use a Service (ClusterIP/NodePort) instead of hostPort for exposing the application.  

7. **Pod priority / preemption issues**
   * **Why it happens** – The pod has a low priority and the scheduler cannot preempt higher‑priority pods to free resources. In clusters where preemption is disabled, the pod will stay Pending.
   * **How to verify** – Look for events mentioning “preemption is disabled” or “insufficient priority”. Check the pod’s `priorityClassName`.
   * **How to fix**  
     * Increase the pod’s priority (assign a higher `PriorityClass`).  
     * Enable preemption in the scheduler configuration if appropriate.  

8. **Scheduler misconfiguration or bugs**
   * **Why it happens** – Rarely, custom scheduler settings (e.g., wrong `schedulerName`) or bugs can prevent normal scheduling.
   * **How to verify** – The pod’s `spec.schedulerName` may point to a non‑existent scheduler. Scheduler events will show “no active scheduler found”.
   * **How to fix**  
     * Reset the `schedulerName` to the default (`default-scheduler`) or ensure the custom scheduler is running correctly.  

---

### General verification workflow (when you regain access)

1. **Inspect pod events** – they give the exact reason the scheduler rejected the pod.  
2. **Check node health and resource availability** – ensure at least one node is Ready and has enough free CPU/memory.  
3. **Review pod spec for selectors, affinities, tolerations, hostPort, PVCs, and priority** – compare these against the current cluster state.  
4. **Address the first failing check** – most issues are resolved by adjusting resources, fixing selectors/taints, or unblocking PVC provisioning.  

By following the order above, you’ll usually pinpoint the root cause quickly and be able to apply the appropriate fix to get `s05-pending` out of the Pending state.
