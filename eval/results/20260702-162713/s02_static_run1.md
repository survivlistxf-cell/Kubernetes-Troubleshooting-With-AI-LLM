# s02 | mode=static | run=1 | 11.8s | needs_search=0

**Q:** My pod 's02-imagepull' in namespace 'kubexplain-eval' will not start. What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** image cannot be pulled (bad name/tag or missing credentials); fix image reference

---

## Most likely reasons why **s02‑imagepull** never starts (ordered by probability)

### 1. Image pull failure (ImagePullBackOff / ErrImagePull)  
**Why it happens**  
- Wrong image name or tag.  
- Image does not exist in the registry.  
- Registry requires authentication and the pod has no or wrong `imagePullSecrets`.  
- The node cannot reach the registry (DNS, proxy, firewall, VPC routing).  

**How to verify**  
- Look at the pod’s events (`kubectl describe pod s02-imagepull -n kubexplain-eval`).  
- Check the container status line – it will show `ImagePullBackOff` or `ErrImagePull` with a reason such as *pull image failed: repository not found* or *authentication required*.  
- Run `kubectl get secret` to see whether the secret referenced in `imagePullSecrets` exists and is in the same namespace.  
- From a node (or a temporary pod) try pulling the image manually: `docker pull <image>` or `ctr images pull <image>` to see the exact error.  

**Fixes**  
- Correct the image name/tag in the pod spec (`kubectl edit pod …` or update the Deployment/Job).  
- Create or update an `imagePullSecret` with valid credentials and reference it in the pod (`imagePullSecrets: - name: my‑registry‑secret`).  
- Ensure the node can resolve and reach the registry (check DNS, proxy settings, firewall rules).  
- If the registry is private and you are using a service‑account token, bind the appropriate `imagePullSecrets` to the service account.  

---

### 2. Pod stuck in **Pending** because it cannot be scheduled  
**Why it happens**  
- No node has enough CPU / memory for the pod’s resource requests.  
- Node selector, node affinity, or required tolerations prevent placement.  
- All nodes are tainted with a taint that the pod does not tolerate.  

**How to verify**  
- `kubectl describe pod …` – the **Events** section will contain messages like *0/3 nodes are available: 3 Insufficient cpu, 3 node(s) had taints that the pod didn’t tolerate*.  
- `kubectl get nodes` and `kubectl describe node <node>` to see allocatable resources and taints.  
- Inspect the pod spec for `nodeSelector`, `affinity`, `tolerations`, and `resources.requests`.  

**Fixes**  
- Reduce the pod’s resource requests or increase node capacity (add nodes or resize existing ones).  
- Adjust or remove the `nodeSelector` / `affinity` rules, or add matching labels to a node.  
- Add the required tolerations to the pod, or remove the offending taint from the node (`kubectl taint nodes <node> key:NoSchedule-`).  

---

### 3. Service‑account / RBAC preventing image pull (rare but possible)  
**Why it happens**  
- The pod’s service account lacks permission to read the secret that contains the registry credentials.  

**How to verify**  
- `kubectl get pod s02-imagepull -o yaml` – note the `serviceAccountName`.  
- `kubectl describe serviceaccount <name> -n kubexplain-eval` – see which secrets are attached.  
- `kubectl auth can-i get secret <secret> --as=system:serviceaccount:kubexplain-eval:<serviceaccount>` to test the permission.  

**Fixes**  
- Bind a role that allows `get` on the secret to the service account, or attach the secret directly to the service account (`kubectl secret link`).  

---

### 4. ImagePullPolicy forces a fresh pull of a non‑existent tag  
**Why it happens**  
- `imagePullPolicy: Always` (or default for `:latest`) makes the kubelet try to pull the image on every start, even if the tag was deleted from the registry.  

**How to verify**  
- Check the pod spec (`imagePullPolicy` field).  
- Look at the error message – it will say *manifest for <image>:latest not found*.  

**Fixes**  
- Use an immutable tag (e.g., `v1.2.3`) and set `imagePullPolicy: IfNotPresent`.  
- If you really need `Always`, make sure the tag exists in the registry.  

---

### 5. Node‑level problems (kubelet down, disk pressure, network plugin)  
**Why it happens**  
- The node where the pod was scheduled is NotReady, under disk pressure, or the kubelet cannot start containers.  

**How to verify**  
- `kubectl get node` – look for `Ready` status and any `DiskPressure`, `MemoryPressure`, `NetworkUnavailable` conditions.  
- `kubectl describe node <node>` – check the **Conditions** and **Events** sections.  

**Fixes**  
- Resolve the underlying node issue (free disk space, restart kubelet, fix CNI plugin).  
- If the node cannot be recovered quickly, evict the pod (`kubectl delete pod …`) so it can be rescheduled onto a healthy node.  

---

## Quick verification checklist you can run later

```bash
# 1. See why the pod is not running
kubectl describe pod s02-imagepull -n kubexplain-eval

# 2. Check the container status line for ImagePullBackOff / ErrImagePull
kubectl get pod s02-imagepull -n kubexplain-eval -o jsonpath='{.status.containerStatuses[*].state}'

# 3. Verify imagePullSecrets (if any)
kubectl get secret -n kubexplain-eval

# 4. Look at node resources / taints
kubectl get nodes
kubectl describe node <node-name>

# 5. Inspect pod spec for selectors, affinity, tolerations, resources
kubectl get pod s02-imagepull -n kubexplain-eval -o yaml
```

Run the above commands when you regain access; the output will point you to the exact cause from the list above, and you can apply the corresponding fix.
