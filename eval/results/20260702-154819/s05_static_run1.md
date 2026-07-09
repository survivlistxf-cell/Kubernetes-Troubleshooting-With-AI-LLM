# s05 | mode=static | run=1 | 10.4s | needs_search=0

**Q:** My pod 's05-pending' in namespace 'kubexplain-eval' is stuck in Pending state. What is the root cause and how do I fix it?

**Expected:** no node satisfies the resource requests; lower requests or add capacity

---

## Why the pod stays **Pending**

The scheduler tried to place **s05‑pending** on every node and failed.  
The event line tells us exactly why:

```
0/6 nodes are available:
  1 node(s) had untolerated taint {dedicated: ollama},
  2 Insufficient cpu,
  2 Insufficient memory,
  3 node(s) had untolerated taint {node-role.kubernetes.io/control-plane: }.
preemption: 0/6 nodes are available: 2 No preemption victims found for incoming pod,
4 Preemption is not helpful for scheduling.
```

Two independent problems are preventing the pod from being scheduled:

| Problem | What it means | Why it matters for this pod |
|---------|---------------|-----------------------------|
| **Untolerated taints** | Some nodes are marked with taints (`dedicated=ollama` and the default control‑plane taint). A pod without a matching toleration will never be placed on those nodes. | Your pod has no `tolerations` for those taints, so the scheduler discards those nodes. |
| **Insufficient resources** | The pod’s **resource requests** exceed what any node can currently offer. | The pod asks for **cpu: 500** (interpreted as 500 mCPU) **and memory: 1000 Gi**. No node in the cluster has a terabyte of free RAM, so the scheduler reports “Insufficient memory”. The CPU request also exceeds the free CPU on most nodes. |

Because **both** conditions are true on every node, the pod never becomes `PodScheduled = True`.

---

## How to fix it

You have three levers to make the pod schedulable:

### 1. Reduce the pod’s resource requests
The memory request of **1000 Gi** is unrealistic for a simple `busybox` container.

```yaml
resources:
  requests:
    cpu: "100m"          # or whatever the container really needs
    memory: "64Mi"       # a typical busybox footprint
  limits:
    cpu: "200m"
    memory: "128Mi"
```

*Why*: With realistic requests the scheduler will find nodes that have enough free capacity.

### 2. Add tolerations (or remove the taints) if you really need those nodes
If you intend the pod to run on a node that carries the `dedicated=ollama` or the control‑plane taint, add matching tolerations:

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

*Why*: This tells the scheduler that the pod is allowed to ignore those taints.

### 3. Choose a node that matches the pod’s constraints
If you have a dedicated worker node without those taints and with enough capacity, you can explicitly target it with a `nodeSelector` or `nodeAffinity`:

```yaml
nodeSelector:
  kubernetes.io/hostname: <worker-node-name>
```

or

```yaml
affinity:
  nodeAffinity:
    requiredDuringSchedulingIgnoredDuringExecution:
      nodeSelectorTerms:
      - matchExpressions:
        - key: dedicated
          operator: NotIn
          values: ["ollama"]
```

*Why*: This narrows the scheduler’s search to nodes that can actually host the pod.

---

## Recommended step‑by‑step remediation

1. **Inspect node capacity** (optional, just to confirm the numbers you’re working with):

   ```bash
   kubectl top nodes
   ```

2. **Edit the pod (or its Deployment/Job) to lower the requests**:

   ```bash
   kubectl edit pod s05-pending -n kubexplain-eval
   ```

   Replace the `resources.requests` block with realistic values (e.g., `cpu: "100m"`, `memory: "64Mi"`). Save and exit.

3. **If you still need the pod on a tainted node**, add the appropriate tolerations as shown above (again via `kubectl edit` or by updating the manifest).

4. **Re‑apply the manifest** (if you edited a higher‑level controller like a Deployment):

   ```bash
   kubectl apply -f <your‑manifest>.yaml
   ```

5. **Verify the pod status**:

   ```bash
   kubectl get pod s05-pending -n kubexplain-eval -w
   ```

   The pod should transition to `Running` once the scheduler finds a suitable node.

---

### Quick checklist

- [ ] **Resource requests** are realistic for the container’s workload.  
- [ ] **Tolerations** match any node taints you intend to use.  
- [ ] **Node selectors / affinity** (if used) point to nodes that actually have the needed capacity.  

Fixing either the resource request or the tolerations (or both) will allow the scheduler to place the pod, and the pod will move out of the `Pending` state.
