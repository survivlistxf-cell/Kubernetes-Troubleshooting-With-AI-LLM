# s05 | mode=none | run=1 | 8.5s | needs_search=0

**Q:** My pod 's05-pending' in namespace 'kubexplain-eval' is stuck in Pending state. What is the root cause and how do I fix it?

**Expected:** no node satisfies the resource requests; lower requests or add capacity

---

### What the `describe` output is telling you  

| Observation | Why it matters |
|-------------|----------------|
| **Pod status = Pending** and **Node = <none>** | The scheduler could not find any node that satisfies the pod’s requirements. |
| **Events – FailedScheduling** | The scheduler tried all nodes and rejected each one for one (or more) of the following reasons: |
| • `1 node(s) had untolerated taint {dedicated: ollama}` | The node is tainted with `dedicated=ollama`. Your pod does **not** have a matching toleration, so the scheduler will not place the pod there. |
| • `3 node(s) had untolerated taint {node‑role.kubernetes.io/control‑plane: }` | Control‑plane nodes are tainted by default (`node-role.kubernetes.io/control-plane`). Again, no toleration → the pod is excluded. |
| • `2 Insufficient cpu` / `2 Insufficient memory` | The pod’s **resource requests** exceed the free capacity on the remaining nodes. |
| • `preemption: 0/6 nodes are available: 2 No preemption victims found … 4 Preemption is not helpful` | Even pre‑empting lower‑priority pods would not free enough resources, so the scheduler gives up. |
| **Pod spec – Requests**<br>`cpu: 500` (≈ 500 mCPU) <br>`memory: 1000Gi` | A request of **1 TiB of RAM** is far larger than any typical node in a cluster. This alone guarantees “Insufficient memory”. |

#### Root cause (in plain terms  

1. **Untolerated taints** – the pod is not allowed to run on nodes that carry the `dedicated=ollama` or the control‑plane taint.  
2. **Impossible memory request** – asking for 1000 GiB of RAM cannot be satisfied by any node in a normal cluster.  
3. **CPU request is also high** (500 m) relative to the remaining capacity on the few nodes that are not tainted, leading to “Insufficient cpu”.  

Because the scheduler must satisfy **all** constraints, the pod remains stuck in *Pending*.

---

### How to fix it  

You have three independent levers:

1. **Adjust the resource requests**  
   * Reduce the memory request to a realistic value (e.g., `100Mi`‑`256Mi` for a simple `busybox` that only sleeps).  
   * Keep the CPU request modest (e.g., `100m`‑`200m`).  
   * After changing the pod spec, the scheduler will re‑evaluate and will likely find a node that has enough free capacity.

2. **Add tolerations (or remove the taints)**  
   * If you really need to run on a node that is tainted `dedicated=ollama`, add a matching toleration to the pod:  

     ```yaml
     tolerations:
     - key: "dedicated"
       operator: "Equal"
       value: "ollama"
       effect: "NoSchedule"
     ```  

   * If you want to run on control‑plane nodes (generally not recommended for production workloads), add a toleration for `node-role.kubernetes.io/control-plane`.  
   * Alternatively, **remove** the taints from the nodes (e.g., `kubectl taint nodes <node> dedicated-`), but be aware that the taint may have been placed intentionally to isolate certain workloads.

3. **Add capacity** (optional)  
   * If the cluster truly needs to host a pod that requires a lot of memory/CPU, provision additional worker nodes with sufficient resources.  
   * For a test scenario, you could also create a dedicated node pool that is **not** tainted and has the needed capacity.

---

### Recommended step‑by‑step (no‑command version  

1. **Edit the pod (or its Deployment/Job) to lower the memory request** to something realistic for a `busybox` sleep container (e.g., `128Mi`).  
2. **Optionally lower the CPU request** to `100m`‑`200m`.  
3. **Decide whether the pod should be allowed on the tainted nodes**:  
   * If yes, add the appropriate tolerations to the pod spec.  
   * If no, leave the tolerations out and let the scheduler pick an untainted node.  
4. **Apply the updated manifest** (or delete & recreate the pod). The scheduler will immediately re‑attempt placement.  
5. **Verify** that the pod reaches `Running` state (`kubectl get pod s05-pending -n kubexplain-eval`).  

If after adjusting resources and tolerations the pod still cannot be scheduled, the next thing to check is the overall cluster capacity: ensure there is at least one node without the conflicting taints that has enough free CPU and memory to satisfy the (now realistic) requests.

---

### Quick sanity check  

* **Memory request**: `1000Gi` → *Impossible* for most clusters.  
* **Taints**: `dedicated=ollama` and `node-role.kubernetes.io/control-plane` → *Pod lacks tolerations*.  

Fixing **both** of these will almost certainly move the pod out of the Pending state.
