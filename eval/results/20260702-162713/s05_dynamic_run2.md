# s05 | mode=dynamic | run=2 | 16.4s | needs_search=0

**Q:** My pod 's05-pending' in namespace 'kubexplain-eval' is stuck in Pending state. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** no node satisfies the resource requests; lower requests or add capacity

---

## Most likely reasons a pod stays **Pending** (ordered by probability)

| # | Reason | Why it’s common | How to verify (when you can run `kubectl`) | Typical fix |
|---|--------|----------------|--------------------------------------------|-------------|
| 1 | **Insufficient CPU / memory on every node** | The scheduler rejects the pod if the requested resources don’t fit on any node. The docs call this the “not enough resources” case. | ```bash\nkubectl describe pod s05-pending -n kubexplain-eval\n```Look at the **Events** section – you’ll see messages like *FailedScheduling – pod … failed to fit in any node* and a line that mentions “Node didn’t have enough resource: CPU, requested: …”. | • Reduce the pod’s `resources.requests`/`limits`.<br>• Scale the Deployment down (fewer replicas).<br>• Add more worker nodes or increase node capacity. |
| 2 | **`hostPort` conflict** | Binding a container to a specific host port limits the pod to nodes that have that port free. If every node already uses the port, the pod can’t be placed. | ```bash\nkubectl describe pod s05-pending -n kubexplain-eval\n```If the pod spec contains `hostPort: <num>`, the scheduler event will say *failed to fit because of hostPort conflict*. | • Remove the `hostPort` if you don’t need it and expose the service via a `Service` object.<br>• If you must use a hostPort, ensure at most one pod per node uses that port, or add more nodes. |
| 3 | **Node selector / node affinity mismatch** | Pods that request a specific label (e.g., `nodeSelector: {"disktype":"ssd"}`) can only land on nodes that carry that label. If no node matches, the pod stays pending. | ```bash\nkubectl get pod s05-pending -n kubexplain-eval -o yaml | grep -A3 nodeSelector\n```or check the `affinity` section. Then list nodes and their labels: `kubectl get nodes --show-labels`. | • Add the required label to one or more nodes (`kubectl label node <node> disktype=ssd`).<br>• Adjust or remove the selector/affinity in the pod spec. |
| 4 | **Taints on all nodes without matching tolerations** | Nodes may be tainted (e.g., `key=dedicated:NoSchedule`). A pod without a matching `tolerations` field will be rejected. | ```bash\nkubectl describe node <node-name>\n```Look for **Taints**. Then inspect the pod spec for `tolerations`. | • Add an appropriate toleration to the pod spec.<br>• Remove or change the taint on the nodes if it’s not needed. |
| 5 | **PersistentVolumeClaim (PVC) pending** | If the pod mounts a PVC that is still **Pending** (e.g., waiting for a storage class to provision), the pod cannot be scheduled. | ```bash\nkubectl get pvc -n kubexplain-eval\n```If any PVC shows `STATUS: Pending`, the pod will stay pending. | • Verify the storage class exists and has enough capacity.<br>• Fix the PVC spec (correct `storageClassName`, size, access modes).<br>• Create the missing PV manually if dynamic provisioning isn’t available. |
| 6 | **Pod priority / pre‑emption issues** | A low‑priority pod may be blocked because higher‑priority pods are already occupying all resources, and pre‑emption is disabled. | ```bash\nkubectl get pod s05-pending -n kubexplain-eval -o yaml | grep priorityClassName\n```Check the cluster’s `PriorityClass` objects and the scheduler’s `preemptionPolicy`. | • Increase the pod’s priority (assign a higher `PriorityClass`).<br>• Enable pre‑emption or free up resources by scaling down other workloads. |
| 7 | **Missing or mis‑typed `apiVersion`/`kind` in the manifest** | A malformed manifest can be accepted by the API server but result in a pod that never reaches a runnable state (e.g., unknown fields are ignored). | ```bash\nkubectl get pod s05-pending -n kubexplain-eval -o yaml | grep -i unknown\n```If you see “unknown field” warnings in the API server logs, the pod spec may be incomplete. | • Re‑apply the manifest with `--validate` to catch schema errors: `kubectl apply --validate -f <file>.yaml`.<br>• Correct any misspelled keys (e.g., `commnd` → `command`). |
| 8 | **Cluster‑wide scheduling limits (e.g., `PodDisruptionBudget`, `PodTopologySpread`)** | Advanced policies can prevent a pod from being placed if they would be violated. | ```bash\nkubectl describe pod s05-pending -n kubexplain-eval | grep -i “PodDisruptionBudget\|TopologySpread”\n```| • Adjust the policy to allow the new pod, or create additional replicas of existing pods to satisfy the spread rules. |

---

## How to verify each cause (once you have cluster access)

1. **Run a full description** – the single command `kubectl describe pod s05-pending -n kubexplain-eval` gives you the events that the scheduler recorded. The *Reason* and *Message* fields point directly to the problem (e.g., `FailedScheduling`, `Insufficient cpu`, `HostPort conflict`).

2. **Check node resources** – `kubectl top nodes` (if Metrics Server is installed) or `kubectl get nodes -o yaml` to see `allocatable` vs `capacity`. Compare with the pod’s `resources.requests`.

3. **Inspect the pod spec** – `kubectl get pod s05-pending -n kubexplain-eval -o yaml` and look for:
   * `resources.requests` / `limits`
   * `hostPort`
   * `nodeSelector`, `affinity`, `tolerations`
   * `priorityClassName`
   * volume claims (`volumes` → `persistentVolumeClaim`)

4. **List node labels & taints** –  
   * `kubectl get nodes --show-labels`  
   * `kubectl describe node <node>` (look for **Taints**)

5. **PVC status** – `kubectl get pvc -n kubexplain-eval` and `kubectl describe pvc <pvc>`.

6. **Priority classes** – `kubectl get priorityclass` to see the numeric values and the default class.

7. **Validate the manifest** – if you still have the original YAML, run `kubectl apply --validate -f <file>.yaml --dry-run=client` to catch schema errors before re‑creating the pod.

---

## Quick “fix checklist” you can run when you reconnect

```bash
# 1. See why the scheduler rejected the pod
kubectl describe pod s05-pending -n kubexplain-eval

# 2. If it’s a resource issue, either lower requests or add capacity
#    (example: edit the deployment to request less CPU)
kubectl edit deployment <name> -n kubexplain-eval

# 3. If hostPort is the blocker, remove it or change the port
#    (edit the pod spec or deployment)
kubectl edit deployment <name> -n kubexplain-eval

# 4. If node selector/affinity is wrong, add matching labels to a node
kubectl label node <node> <key>=<value>

# 5. If taints block the pod, add a toleration or remove the taint
kubectl taint node <node> key:NoSchedule-   # removes the taint
# or edit the pod spec to include:
# tolerations:
# - key: "key"
#   operator: "Exists"
#   effect: "NoSchedule"

# 6. If a PVC is pending, check the storage class and provision a volume
kubectl describe pvc <pvc> -n kubexplain-eval

# 7. If priority is too low, assign a higher PriorityClass
kubectl patch pod s05-pending -n kubexplain-eval -p '{"spec":{"priorityClassName":"high-priority"}}'

# 8. After fixing the root cause, delete the stuck pod so it can be recreated
kubectl delete pod s05-pending -n kubexplain-eval
```

Run the relevant commands for the specific cause you discover. Once the underlying issue is resolved, the pod should transition from **Pending** to **Running** (or **CrashLoopBackOff** if there are later container‑level problems).
