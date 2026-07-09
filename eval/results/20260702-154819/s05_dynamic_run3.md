# s05 | mode=dynamic | run=3 | 8.1s | needs_search=0

**Q:** My pod 's05-pending' in namespace 'kubexplain-eval' is stuck in Pending state. What is the root cause and how do I fix it?

**Expected:** no node satisfies the resource requests; lower requests or add capacity

---

### What the `kubectl describe pod` output is telling you  

| Observation | Meaning |
|-------------|----------|
| **Status: Pending** and **PodScheduled = False** | The scheduler could not find a node that satisfies the pod’s requirements. |
| **Requests:**<br>‑ cpu = `500`<br>‑ memory = `1000Gi` | The pod is asking for **500 CPU units** (≈ 500 cores) and **1 TiB of RAM**. No node in the cluster has that much free capacity. |
| **Events:**<br>`0/6 nodes are available: 1 node(s) had untolerated taint {dedicated: ollama}, 2 Insufficient cpu, 2 Insufficient memory, 3 node(s) had untolerated taint {node‑role.kubernetes.io/control‑plane: }.` | The scheduler tried all six nodes and rejected them for three reasons:<br>1. **Untolerated taints** – three nodes are marked as control‑plane and one node carries a custom taint `dedicated=ollama`. Your pod does not tolerate any of these taints, so it will never be placed on those nodes.<br>2. **Insufficient CPU** – two nodes do not have enough free CPU to satisfy the `500` request.<br>3. **Insufficient memory** – two nodes do not have enough free memory to satisfy the `1000Gi` request. |
| **Preemption:** `0/6 nodes are available: 2 No preemption victims found …` | Even if the scheduler tried to evict lower‑priority pods, there are no suitable victims that would free enough resources for this pod. |

#### Root cause  
The pod’s **resource requests are far beyond what any node can provide**, and the pod also **doesn’t tolerate the taints** present on the available nodes. Because both conditions fail, the scheduler cannot place the pod, leaving it stuck in *Pending*.

---

### How to fix it  

#### 1. Reduce the pod’s resource requests (most common fix)  
The pod is asking for 500 CPU and 1 TiB of RAM – values that are unrealistic for a simple `busybox` container.

*Edit the pod (or the Deployment/Job that creates it) and set realistic requests, e.g.:*

```yaml
resources:
  requests:
    cpu: "100m"        # 0.1 CPU
    memory: "64Mi"     # 64 MiB
  limits:
    cpu: "200m"
    memory: "128Mi"
```

After updating, delete/re‑create the pod (or let the controller roll out the change). The scheduler will then be able to find a node with enough free capacity.

#### 2. Add tolerations for the existing taints (if you really need to run on those nodes)  
If you intentionally want the pod to run on a node that is tainted (e.g., the control‑plane node or a node with `dedicated=ollama`), add matching tolerations:

```yaml
tolerations:
- key: "node-role.kubernetes.io/control-plane"
  operator: "Exists"
  effect: "NoSchedule"
- key: "dedicated"
  operator: "Equal"
  value: "ollama"
  effect: "NoSchedule"
```

*Note:* Tolerating a control‑plane taint is generally discouraged for production workloads; it’s better to add dedicated worker nodes.

#### 3. Provide more capacity (add or resize nodes)  
If the workload truly needs large amounts of CPU/memory, you must provision nodes that can satisfy those requests:

- **Scale up** existing node pools (e.g., larger VM types) or
- **Add new nodes** with sufficient resources.

After the new capacity is available, the scheduler will automatically place the pod.

#### 4. Verify the fix  

```bash
kubectl get pod s05-pending -n kubexplain-eval -o wide
kubectl describe pod s05-pending -n kubexplain-eval
```

You should see `STATUS` change to **Running** and the `Node` field populated.

---

### Quick checklist  

- [ ] **Adjust resource requests** to realistic values for a `busybox` container.  
- [ ] **Add tolerations** only if you really need to run on tainted nodes.  
- [ ] **Add capacity** (larger or additional nodes) if the workload truly requires it.  
- [ ] Re‑apply the manifest and confirm the pod reaches **Running**.

Once the pod is scheduled, the `Pending` state will disappear and the container will start executing `sleep 3600`.
