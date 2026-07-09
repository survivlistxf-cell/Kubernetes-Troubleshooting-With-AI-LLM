# s05 | mode=none | run=3 | 9.8s | needs_search=0

**Q:** My pod 's05-pending' in namespace 'kubexplain-eval' is stuck in Pending state. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** no node satisfies the resource requests; lower requests or add capacity

---

## Most likely reasons a pod stays **Pending** (ordered by how often they occur)

1. **Insufficient compute resources (CPU / memory) in the cluster**  
   *Why it happens* – The scheduler can’t find a node that has enough free CPU or memory to satisfy the pod’s `requests`.  
   *How to verify* –  
   - Look at the pod’s `kubectl describe pod s05-pending -n kubexplain-eval` output. Under **Events** you’ll see messages like “0/5 nodes are available: 5 Insufficient cpu, 5 Insufficient memory”.  
   - Check node resource usage (`kubectl top nodes`) or the node‑level `allocatable` vs `capacity` fields.  
   *How to fix* –  
   - Reduce the pod’s resource **requests** (or limits) if they are higher than needed.  
   - Add more nodes or larger instance types to the cluster.  
   - Enable cluster‑autoscaler so new nodes are provisioned automatically when needed.

2. **Node selector / affinity / taints that prevent placement**  
   *Why it happens* – The pod specifies a `nodeSelector`, `nodeAffinity`, or tolerations that don’t match any existing node, or all nodes are tainted with a key the pod doesn’t tolerate.  
   *How to verify* –  
   - In the pod description, look for **Node-Selectors**, **Affinity**, and **Tolerations** sections.  
   - In the **Events** you may see “0/3 nodes are available: 3 node(s) didn’t match node selector, 3 node(s) had taints that the pod didn’t tolerate”.  
   - Run `kubectl get nodes -o wide` and inspect their labels and taints.  
   *How to fix* –  
   - Adjust the pod’s selector/affinity to match an existing node label.  
   - Add the required label to a node, or remove/modify the taint.  
   - Add a matching toleration to the pod if you want it to run on tainted nodes.

3. **PersistentVolumeClaim (PVC) binding delay or failure**  
   *Why it happens* – The pod requests a volume via a PVC that is still **Pending** because no suitable PersistentVolume (PV) exists, or the storage class is mis‑configured.  
   *How to verify* –  
   - In the pod description, under **Volumes** you’ll see the PVC name.  
   - Run `kubectl get pvc -n kubexplain-eval` and check its **STATUS**. If it’s *Pending*, the pod will stay pending too.  
   - Look at the PVC’s events for messages like “no volume plugin found for …” or “waiting for a volume to be bound”.  
   *How to fix* –  
   - Create a PV that satisfies the PVC’s size, access mode, and storage class, or adjust the PVC to request a class that actually exists.  
   - If using dynamic provisioning, verify that the storage class’s provisioner is functional and that the underlying cloud provider has quota/limits available.

4. **Pod is part of a DaemonSet, ReplicaSet, or Deployment with a `nodeName` set incorrectly**  
   *Why it happens* – The controller may have set `spec.nodeName` to a node that no longer exists (e.g., after a node was drained or removed).  
   *How to verify* –  
   - In the pod spec, check for a `nodeName` field.  
   - Compare that name to the current node list (`kubectl get nodes`).  
   *How to fix* –  
   - Delete the pod so the controller can recreate it without the stale `nodeName`.  
   - If the controller itself is mis‑configured, correct the controller’s pod template.

5. **Pod exceeds the namespace’s ResourceQuota**  
   *Why it happens* – The namespace has a `ResourceQuota` that limits total CPU, memory, or PVC count, and the new pod would push the usage over the limit.  
   *How to verify* –  
   - Run `kubectl get quota -n kubexplain-eval` and inspect the **used** vs **hard** values.  
   - In the pod events you may see “exceeded quota: ...”.  
   *How to fix* –  
   - Reduce the pod’s requests, delete other pods to free quota, or increase the quota (if you have permission).  

6. **Admission controller rejection (e.g., PodSecurityPolicy, OPA/Gatekeeper)**  
   *Why it happens* – An admission controller blocks the pod because it violates a policy (e.g., runs as privileged, uses a disallowed hostPath, etc.).  
   *How to verify* –  
   - Check the pod’s **Events** for messages like “denied by PodSecurityPolicy” or “constraint violation”.  
   - Look at the cluster’s admission controller configuration if you have access.  
   *How to fix* –  
   - Adjust the pod spec to comply with the policy (remove privileged flag, use allowed volume types, etc.).  
   - If the policy is too strict for this workload, request a policy change from the cluster admin.

7. **Image pull issues that are reported as scheduling problems**  
   *Why it happens* – Rarely, the scheduler may mark a pod pending if the image pull secret is invalid and the node cannot resolve the image, especially when combined with `imagePullPolicy: Never`.  
   *How to verify* –  
   - Look at the pod events for “Failed to pull image” or “Invalid image name”.  
   - Verify that the image registry is reachable and the secret exists.  
   *How to fix* –  
   - Correct the image name, tag, or registry credentials.  
   - Ensure the node has network access to the registry.

---

### Quick verification checklist (no commands needed)

1. **Events** – The most informative source; they usually state the exact reason (e.g., “Insufficient cpu”, “node(s) didn’t match node selector”, “persistentvolumeclaim not bound”).  
2. **Resource requests vs node capacity** – Compare the pod’s `requests` with the free resources on nodes.  
3. **Node selectors / taints** – Confirm that at least one node satisfies all selectors, affinities, and tolerations.  
4. **PVC status** – If the pod mounts a PVC, the PVC must be **Bound**.  
5. **Namespace quotas** – Ensure the pod’s resources stay within any defined quota.  
6. **Admission policies** – Look for explicit denial messages in events.  

By walking through those items in order, you’ll quickly pinpoint the root cause and apply the corresponding fix. If after checking all of the above the pod is still pending, the next step is to retrieve the full `describe` output and any relevant controller logs for deeper investigation.
