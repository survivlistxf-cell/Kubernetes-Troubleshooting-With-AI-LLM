# s05 | mode=static | run=3 | 11.5s | needs_search=0

**Q:** My pod 's05-pending' in namespace 'kubexplain-eval' is stuck in Pending state. What is the root cause and how do I fix it?

**Expected:** no node satisfies the resource requests; lower requests or add capacity

---

### Why the pod stays **Pending**

| Symptom from `kubectl describe pod` | What it means |
|--------------------------------------|---------------|
| **0/6 nodes are available**          | The scheduler looked at every node in the cluster and found none that could host the pod. |
| **1 node(s) had untolerated taint {dedicated: ollama}** | One node is marked with a custom taint `dedicated=ollama`. Your pod does **not** have a matching toleration, so the scheduler will never place the pod there. |
| **3 node(s) had untolerated taint {node‑role.kubernetes.io/control-plane: }** | Control‑plane nodes are tainted to keep workloads off them. Again, the pod lacks a toleration, so those nodes are ignored. |
| **2 Insufficient cpu, 2 Insufficient memory** | The remaining nodes do not have enough free CPU **or** memory to satisfy the pod’s *resource requests*. |
| **preemption: 0/6 nodes are available … Preemption is not helpful** | Even if the scheduler tried to evict lower‑priority pods, it still couldn’t free enough resources for this pod. |

#### The two fundamental problems

1. **Resource requests are far beyond what any node can provide**  
   - `cpu: 500` → 500 **cores** (or 500 m if the unit is millicores, but the output shows no “m”, so it is interpreted as whole cores).  
   - `memory: 1000Gi` → 1 TiB of RAM.  
   No typical worker node has that much capacity, so the scheduler rejects the pod.

2. **Untolerated taints block placement**  
   - Nodes with `dedicated=ollama` and the default control‑plane taint are excluded because the pod does not declare tolerations for them.

Because both conditions are true, the pod never becomes scheduled.

---

### How to fix it

#### 1. Adjust the pod’s resource requests (most common fix)

If the pod really only needs a small amount of CPU/memory (e.g., a simple `busybox sleep`), lower the requests to realistic values:

```bash
kubectl edit pod s05-pending -n kubexplain-eval
```

Change the `resources` block to something like:

```yaml
resources:
  requests:
    cpu: "100m"        # 0.1 core
    memory: "128Mi"
  limits:
    cpu: "200m"
    memory: "256Mi"
```

Or, if you prefer to recreate the pod with a corrected manifest:

```bash
cat <<EOF > pod.yaml
apiVersion: v1
kind: Pod
metadata:
  name: s05-pending
  namespace: kubexplain-eval
  labels:
    eval-scenario: s05
spec:
  containers:
  - name: app
    image: busybox:1.36
    command: ["sleep","3600"]
    resources:
      requests:
        cpu: "100m"
        memory: "128Mi"
      limits:
        cpu: "200m"
        memory: "256Mi"
EOF

kubectl apply -f pod.yaml
```

After the requests fit within the free capacity of a node, the scheduler will bind the pod.

#### 2. Add a toleration (if you *do* want the pod to run on a tainted node)

If the pod must run on a node that carries the `dedicated=ollama` taint (or on a control‑plane node), add a matching toleration:

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

You can patch the pod (or the pod template if it’s part of a Deployment/Job) like this:

```bash
kubectl patch pod s05-pending -n kubexplain-eval \
  --type='json' -p='[{"op":"add","path":"/spec/tolerations","value":[{"key":"dedicated","operator":"Equal","value":"ollama","effect":"NoSchedule"},{"key":"node-role.kubernetes.io/control-plane","operator":"Exists","effect":"NoSchedule"}]}]'
```

> **Note:** Adding tolerations alone will not solve the *resource* problem; you still need realistic resource requests.

#### 3. Provide more capacity (if the huge request is intentional)

If the pod truly needs ~500 CPU cores and 1 TiB of RAM (e.g., a heavy data‑processing job), you must:

- **Add larger worker nodes** (e.g., instances with enough vCPU and RAM) or
- **Scale out the cluster** and enable **cluster autoscaler** so that new nodes are provisioned automatically when such a pod appears.

Example (cloud‑provider specific) – create a new node pool with larger VM size, then verify the node joins the cluster:

```bash
# Example for GKE (adjust to your provider)
gcloud container node-pools create large-pool \
  --cluster=my-cluster \
  --machine-type=n1-standard-64 \
  --num-nodes=1 \
  --node-taints=dedicated=ollama:NoSchedule
```

After the new node is Ready, the scheduler will be able to place the pod (provided the taint is tolerated).

#### 4. Verify the fix

```bash
kubectl get pod s05-pending -n kubexplain-eval -w
```

The pod should transition from **Pending** → **Running** once the scheduler finds a suitable node.

---

### Quick checklist

- [ ] **Resource requests** are realistic for the cluster’s node sizes.  
- [ ] **Tolerations** match any taints on nodes you intend to use.  
- [ ] If using a **taint‑based node pool**, either add a toleration or remove the taint from the node (if appropriate).  
- [ ] If the workload truly needs massive resources, **add or autoscale** nodes with sufficient capacity.  

Addressing the resource request first will usually resolve the “Insufficient cpu / memory” errors; tolerations are only needed if you deliberately want to schedule onto a tainted node. Once both issues are cleared, the pod will be scheduled and start running.
