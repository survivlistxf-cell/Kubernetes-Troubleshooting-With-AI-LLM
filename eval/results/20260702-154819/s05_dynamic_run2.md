# s05 | mode=dynamic | run=2 | 7.8s | needs_search=0

**Q:** My pod 's05-pending' in namespace 'kubexplain-eval' is stuck in Pending state. What is the root cause and how do I fix it?

**Expected:** no node satisfies the resource requests; lower requests or add capacity

---

### What the `describe` tells us

| Item | Value | Why it matters |
|------|-------|----------------|
| **CPU request** | `500` (interpreted as **500 cores**) | No node in the cluster has that much free CPU. |
| **Memory request** | `1000 Gi` | Likewise, no node has a terabyte of free RAM. |
| **Node‑selector / tolerations** | No selector, but the pod **does not tolerate** the taints `dedicated: ollama` and `node-role.kubernetes.io/control-plane` | The scheduler will skip any node that carries those taints unless the pod explicitly tolerates them. |
| **Events** | `0/6 nodes are available: 1 node(s) had untolerated taint {dedicated: ollama}, 2 Insufficient cpu, 2 Insufficient memory, 3 node(s) had untolerated taint {node-role.kubernetes.io/control-plane: }` | The scheduler tried all six nodes and rejected each for one of the reasons above. |

**Root cause:**  
The pod asks for far more resources than any node can provide **and** it is blocked by two taints that it does not tolerate. Because of both issues the scheduler cannot place the pod, so it stays in **Pending**.

---

### How to fix it

#### 1. Reduce the resource requests (most common fix)

The pod’s container is a simple `busybox sleep 3600`; it does **not** need 500 CPU or 1 TiB of RAM.

```yaml
resources:
  requests:
    cpu: "100m"        # 0.1 core is plenty for a sleep loop
    memory: "64Mi"     # a few megabytes are enough
  limits:
    cpu: "200m"
    memory: "128Mi"
```

Apply the updated manifest (`kubectl apply -f <file>`). After the change the scheduler will find a node that satisfies the modest request.

#### 2. Add a node that can satisfy the current request

If the huge request is intentional (e.g., a workload that really needs that capacity), you must provision additional worker nodes with enough CPU and memory, or scale up existing nodes (larger VM size, more cores, more RAM). Once the cluster has a node with ≥ 500 CPU and ≥ 1 TiB free, the pod will schedule.

#### 3. Tolerate the taints (only if the pod is allowed to run on those nodes)

If the pod *should* be allowed to run on the control‑plane node or on a node tainted with `dedicated: ollama`, add matching tolerations:

```yaml
tolerations:
- key: "dedicated"
  operator: "Equal"
  value: "ollama"
  effect: "NoSchedule"
- key: "node-role.kubernetes.io/control-plane"
  operator: "Exists"
  effect: "NoSchedule"
```

> **Caution:** Running regular workloads on control‑plane nodes is generally discouraged in production clusters because it can affect cluster stability.

#### 4. Combine the fixes

Often the simplest solution is a combination: lower the resource request **and** add a toleration if you really want the pod to land on a tainted node (e.g., a dedicated GPU node).  

---

### Quick checklist

- [ ] **Review the pod spec** – are those CPU/memory numbers realistic?
- [ ] **Edit the manifest** to set appropriate `requests`/`limits`.
- [ ] **Apply** the updated manifest.
- [ ] If you really need the large resources, **scale the cluster** (add/resize nodes).
- [ ] If the pod must run on a tainted node, **add matching tolerations** (or remove the taint from the node if appropriate).

After you make the changes, run `kubectl get pod s05-pending -n kubexplain-eval -w` to watch the pod transition from *Pending* to *Running*. If it still stays pending, re‑run `kubectl describe pod s05-pending -n kubexplain-eval` and look at the *Events* section for the new reason.
