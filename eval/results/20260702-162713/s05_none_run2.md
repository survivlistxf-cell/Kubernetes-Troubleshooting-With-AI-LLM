# s05 | mode=none | run=2 | 16.4s | needs_search=0

**Q:** My pod 's05-pending' in namespace 'kubexplain-eval' is stuck in Pending state. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** no node satisfies the resource requests; lower requests or add capacity

---

## Most likely reasons a pod stays **Pending**

Below the causes are ordered from the ones that most often produce a Pending pod to the rarer edge‑cases.  For each cause you’ll see:

* **What to look for** – the symptom or clue in the pod’s events / cluster state.  
* **How to verify** – the `kubectl` (or API) checks you would run when you regain access.  
* **Typical fix** – the change you would apply to get the pod scheduled.

---

### 1. Not enough CPU / memory (or other resources) in the cluster  

**What you’ll see**  
* `0/5 nodes are available: 5 Insufficient cpu` (or `memory`, `ephemeral-storage`, etc.) in the pod’s `Events` section.  

**How to verify**  
```bash
kubectl describe pod s05-pending -n kubexplain-eval
kubectl get nodes -o wide
kubectl top nodes               # shows current allocatable vs. used resources
```
* Compare the pod’s `resources.requests` against the sum of free resources on all nodes.  

**Typical fix**  
* Reduce the pod’s `requests`/`limits`.  
* Add more nodes or increase node size.  
* If you have a cluster autoscaler, ensure it’s enabled and not throttled.

---

### 2. Node selector / affinity rules that no node satisfies  

**What you’ll see**  
* Event like `0/5 nodes are available: 5 node(s) didn't match node selector`.  
* In the pod spec you’ll find `nodeSelector`, `nodeAffinity`, or `podAffinity` that reference labels not present on any node.  

**How to verify**  
```bash
kubectl get pod s05-pending -n kubexplain-eval -o yaml | grep -A5 selector
kubectl get nodes --show-labels
```
* List the node labels and compare them with the selector/affinity expressions.  

**Typical fix**  
* Add the missing label to a node (`kubectl label node <node> key=value`).  
* Adjust or remove the selector/affinity in the pod (or Deployment/StatefulSet) definition.  

---

### 3. Taints on all nodes without matching tolerations  

**What you’ll see**  
* Event like `0/5 nodes are available: 5 node(s) had taint {key=value:NoSchedule}`.  

**How to verify**  
```bash
kubectl describe node <node-name> | grep -i taint
kubectl get pod s05-pending -n kubexplain-eval -o yaml | grep -i tolerations
```
* Check the list of taints on each node and the pod’s `tolerations`.  

**Typical fix**  
* Add an appropriate toleration to the pod spec.  
* Remove or modify the taint on the node if it’s not needed.  

---

### 4. PersistentVolumeClaim (PVC) not bound / storage class issues  

**What you’ll see**  
* Event: `0/5 nodes are available: 5 node(s) didn't match pod affinity/anti-affinity` is less common; more typical is the pod never leaves Pending because the PVC is still `Pending`.  
* `kubectl get pvc` shows `STATUS: Pending`.  

**How to verify**  
```bash
kubectl get pvc -n kubexplain-eval
kubectl describe pvc <pvc-name> -n kubexplain-eval
```
* Look for messages like “no volume plugin found for …” or “waiting for a volume to be provisioned”.  

**Typical fix**  
* Ensure a matching `StorageClass` exists and is default (or specify it in the PVC).  
* Verify that the underlying storage provider has capacity.  
* If using static provisioning, create the PV that satisfies the PVC’s size and access mode.  

---

### 5. Exceeded ResourceQuota or LimitRange in the namespace  

**What you’ll see**  
* Event: `0/5 nodes are available: 5 pod(s) exceeded quota`.  
* `kubectl get resourcequota -n kubexplain-eval` shows used > hard for CPU, memory, or object count.  

**How to verify**  
```bash
kubectl get resourcequota -n kubexplain-eval
kubectl describe resourcequota <quota-name> -n kubexplain-eval
```
* Check the `used` vs `hard` values for `pods`, `cpu`, `memory`, etc.  

**Typical fix**  
* Reduce the pod’s resource requests.  
* Increase the quota (if you have permission) or request a larger quota from the admin.  

---

### 6. Scheduler disabled or mis‑configured  

**What you’ll see**  
* No scheduling events at all; the pod stays in `Pending` with no “FailedScheduling” messages.  
* The `kube-scheduler` pod may be CrashLoopBackOff or not running.  

**How to verify**  
```bash
kubectl get pods -n kube-system -l component=kube-scheduler
kubectl logs <scheduler-pod> -n kube-system
```
* Look for errors in the scheduler logs.  

**Typical fix**  
* Restart the scheduler deployment/statefulset.  
* Fix any configuration errors (e.g., invalid policy files).  

---

### 7. NetworkPolicy that blocks the pod from contacting the API server (rare for Pending)  

**What you’ll see**  
* The pod never gets scheduled because the API server cannot reach the node’s kubelet to report the pod.  
* Usually accompanied by other symptoms (pods in other namespaces also stuck).  

**How to verify**  
```bash
kubectl get networkpolicy -A
kubectl describe networkpolicy <policy-name> -n <ns>
```
* Ensure there’s an allow rule for traffic from the kube‑apiserver to the node’s kubelet port (10250).  

**Typical fix**  
* Adjust the NetworkPolicy to permit the required traffic, or temporarily delete it for debugging.  

---

### 8. Pod exceeds the maximum number of pods per node (Node’s `maxPods` limit)  

**What you’ll see**  
* Event: `0/5 nodes are available: 5 node(s) had too many pods`.  

**How to verify**  
```bash
kubectl describe node <node-name> | grep -i pods
```
* Compare `pods` count with the node’s `maxPods` setting (often 110 on many clusters).  

**Typical fix**  
* Add more nodes to increase capacity.  
* Reduce the number of running pods (e.g., delete unused workloads).  

---

### 9. Pod uses a non‑existent ServiceAccount or secret  

**What you’ll see**  
* Event: `serviceaccount "xyz" not found`.  
* The pod may stay Pending because the scheduler cannot resolve the ServiceAccount token volume.  

**How to verify**  
```bash
kubectl get serviceaccount -n kubexplain-eval
kubectl describe pod s05-pending -n kubexplain-eval | grep -i serviceaccount
```
* Confirm the referenced ServiceAccount exists.  

**Typical fix**  
* Create the missing ServiceAccount or correct the pod spec to reference an existing one.  

---

### 10. Cluster‑wide issues (API server overload, etc.)  

**What you’ll see**  
* Many pods across namespaces stuck in Pending, API server response times high, or `kubectl` commands time out.  

**How to verify**  
```bash
kubectl get componentstatuses
kubectl top pod -A   # see if control‑plane pods are healthy
```
* Look for `Unhealthy` components or high CPU/memory on `kube-apiserver`, `etcd`, etc.  

**Typical fix**  
* Scale up control‑plane resources, investigate etcd health, or restart problematic control‑plane pods.  

---

## Quick checklist you can run when you regain access

1. **Describe the pod** – look at the `Events` section for the first failure reason.  
2. **Check node resources** – `kubectl top nodes` vs. pod requests.  
3. **Inspect selectors / affinities** – compare pod spec with node labels.  
4. **Review taints & tolerations** – `kubectl describe node` and pod tolerations.  
5. **Validate PVCs** – `kubectl get pvc` and `kubectl describe pvc`.  
6. **Confirm quotas** – `kubectl get resourcequota`.  
7. **Ensure scheduler is healthy** – `kubectl get pods -n kube-system -l component=kube-scheduler`.  

Address the first failing check you encounter; that usually resolves the Pending state. If you still see the pod stuck after fixing the top‑most cause, move down the list until the pod schedules successfully. Good luck!
