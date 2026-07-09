# s05 | mode=static | run=2 | 10.1s | needs_search=0

**Q:** My pod 's05-pending' in namespace 'kubexplain-eval' is stuck in Pending state. What is the root cause and how do I fix it?

**Expected:** no node satisfies the resource requests; lower requests or add capacity

---

## Why **s05‑pending** stays in **Pending**

| Symptom (from `kubectl describe pod`) | What it means |
|---------------------------------------|---------------|
| `0/6 nodes are available`            | The scheduler looked at every node in the cluster and found none that could host the pod. |
| `1 node(s) had untolerated taint {dedicated: ollama}` | One node is marked with a custom taint `dedicated=ollama`. Your pod does **not** have a matching toleration, so the scheduler will never place the pod there. |
| `2 Insufficient cpu`  <br> `2 Insufficient memory` | The remaining nodes do not have enough **allocatable** CPU or memory to satisfy the pod’s *requests*. |
| `3 node(s) had untolerated taint {node‑role.kubernetes.io/control‑plane: }` | The control‑plane nodes are tainted as “control‑plane”. Pods that are not system pods must tolerate this taint to be scheduled on those nodes. |
| `preemption: … No preemption victims found … Preemption is not helpful` | Even if the scheduler tried to evict other pods, none could be pre‑empted to free the required resources. |

### The two fundamental problems

1. **Resource requests are far beyond what any node can provide**  
   ```text
   cpu request: 500          (500 cores – not 500 m)
   memory request: 1000Gi   (≈1 TiB)
   ```
   Your cluster nodes have only a couple of CPUs and a few GiB of RAM (see the node description in the evidence). No node can satisfy those numbers.

2. **Untolerated taints**  
   - `dedicated=ollama` on one node.  
   - `node-role.kubernetes.io/control-plane` on the control‑plane nodes.  

   Because the pod has no `tolerations` for these taints, the scheduler discards those nodes outright.

## How to fix it

You have three broad options: **reduce the pod’s demands**, **make the pod tolerable of the existing taints**, or **add capacity that matches the pod’s needs**. Choose the combination that matches your intended workload.

### 1. Reduce the resource requests (most common)

If the pod does not really need 500 CPU and 1 TiB of RAM, edit the manifest:

```yaml
resources:
  requests:
    cpu: "500m"        # 0.5 CPU
    memory: "256Mi"    # 256 MiB
  limits:
    cpu: "1"           # optional
    memory: "512Mi"
```

Apply the change:

```bash
kubectl edit pod s05-pending -n kubexplain-eval
# or, if you have the YAML in a file:
kubectl apply -f pod.yaml
```

After the request fits within a node’s allocatable resources, the scheduler will place the pod.

### 2. Add a toleration (if you really need that node)

If you *do* need to run on the node that has the `dedicated=ollama` taint, add a matching toleration:

```yaml
tolerations:
- key: "dedicated"
  operator: "Equal"
  value: "ollama"
  effect: "NoSchedule"
```

For the control‑plane nodes (generally not meant for user workloads) you would need:

```yaml
- key: "node-role.kubernetes.io/control-plane"
  operator: "Exists"
  effect: "NoSchedule"
```

Add the tolerations to the pod spec and re‑apply.

### 3. Provide enough capacity

If the pod truly needs 500 CPU and 1 TiB of RAM (e.g., a heavy data‑processing job), you must provision nodes that can satisfy those requests:

* Add a new worker node with at least 500 CPU and 1 TiB RAM, **without** the taints that block the pod, or
* Remove or relax the taints on an existing node (if appropriate) and ensure the node has the required resources.

Example to remove a taint from a node (use with caution):

```bash
kubectl taint node <node-name> dedicated-ollama-
kubectl taint node <node-name> node-role.kubernetes.io/control-plane-
```

### 4. Verify the fix

After you have adjusted resources, tolerations, or node capacity, run:

```bash
kubectl get pod s05-pending -n kubexplain-eval -w
```

The pod should transition from **Pending** to **Running** (or **ContainerCreating** while the image pulls).

## Quick checklist

- [ ] **Check the pod’s resource requests** – are they realistic for your cluster size?
- [ ] **Add/adjust tolerations** for any taints that block scheduling.
- [ ] **Confirm node capacity** (`kubectl describe node <node>`).  
- [ ] **Re‑apply the pod manifest** or delete & recreate the pod after edits.
- [ ] **Watch the pod** until it reaches `Running`.

By addressing the oversized resource requests and the untolerated taints, the scheduler will be able to find a suitable node and the pod will leave the `Pending` state.
