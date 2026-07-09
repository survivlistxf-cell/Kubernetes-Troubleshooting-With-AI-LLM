# s05 | mode=dynamic | run=1 | 8.3s | needs_search=0

**Q:** My pod 's05-pending' in namespace 'kubexplain-eval' is stuck in Pending state. What is the root cause and how do I fix it?

**Expected:** no node satisfies the resource requests; lower requests or add capacity

---

### What the `describe` output is telling you

| Observation | Why it matters |
|-------------|----------------|
| **CPU request = `500`** (no unit) and **memory request = `1000Gi`** | The scheduler interprets `500` as **500 cores** and `1000Gi` as **1 TiB** of RAM. No node in a typical cluster has that much capacity, so the pod can’t be placed. |
| **Events** – “2 Insufficient cpu, 2 Insufficient memory” | Confirms the scheduler is rejecting the pod because the requested resources exceed what any node can offer. |
| **Events** – “1 node(s) had untolerated taint `{dedicated: ollama}`” and “3 node(s) had untolerated taint `{node‑role.kubernetes.io/control‑plane: }`” | The pod does not have tolerations for those taints, so even if a node had enough resources it would still be ignored. |
| **PodScheduled = False** | The pod never got bound to a node because of the above constraints. |

**Root cause:**  
The pod’s resource **requests are far larger than any node can provide**, and the pod also lacks tolerations for the taints present on the available nodes. The scheduler therefore reports “0/6 nodes are available”.

---

### How to fix it

You have three broad options, depending on what you actually need:

#### 1. Reduce the pod’s resource requests (most common)

If the pod only needs a tiny amount of CPU/memory (the `busybox` command `sleep 3600` hardly needs anything), change the manifest to realistic values, e.g.:

```yaml
resources:
  requests:
    cpu: "100m"        # 0.1 core
    memory: "64Mi"
  limits:
    cpu: "200m"
    memory: "128Mi"
```

Apply the corrected manifest:

```bash
kubectl apply -f <your‑pod‑file>.yaml
```

#### 2. Add tolerations (if you really want the pod to run on a tainted node)

If the node you intend to use is intentionally tainted (e.g., a dedicated “ollama” node or a control‑plane node) you must add matching tolerations to the pod spec:

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

Then re‑apply the pod.

#### 3. Add capacity to the cluster (if the huge request is intentional)

If the pod truly needs a massive amount of CPU and memory (e.g., a data‑processing job), you must:

* **Scale up** existing nodes (increase their vCPU/RAM) or
* **Add new nodes** with enough capacity, or
* **Use a node pool** that matches the required size (e.g., a “high‑memory” machine type).

After the new capacity is available, the scheduler will be able to bind the pod (provided you also handle any taints).

---

### Quick checklist to verify the fix

1. **Describe the pod again** – `kubectl describe pod s05-pending -n kubexplain-eval`  
   *Look for `PodScheduled: True` and no “FailedScheduling” events.*

2. **Check node resources** – `kubectl top nodes` (or `kubectl get nodes -o wide`) to confirm enough free CPU/memory.

3. **Confirm tolerations** – `kubectl get pod s05-pending -n kubexplain-eval -o yaml | grep tolerations -A5` if you added them.

---

### TL;DR

*Your pod asks for 500 CPU cores and 1 TiB of RAM and has no tolerations for the taints on your nodes.*  
**Fix:** lower the resource requests to realistic values (e.g., `100m` CPU, `64Mi` memory), optionally add tolerations if you need to run on a tainted node, or provision larger nodes if the huge request is intentional. Once the pod’s spec matches the cluster’s capacity, the scheduler will place it and the pod will move out of the `Pending` state.
