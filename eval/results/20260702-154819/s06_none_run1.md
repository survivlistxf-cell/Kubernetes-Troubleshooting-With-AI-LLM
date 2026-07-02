# s06 | mode=none | run=1 | 7.2s | needs_search=0

**Q:** An application running as service account 's06-limited-sa' in namespace 'kubexplain-eval' gets an error when listing secrets. The logs are attached. What is the root cause and how do I fix it?

**Expected:** RBAC: role lacks 'list secrets' permission (Forbidden); add the missing rule

---

### What is happening?

The pod **s06‑rbac** runs as the service account **`s06‑limited‑sa`** in the namespace **`kubexplain‑eval`**.  
When the container executes

```bash
kubectl get secrets -n kubexplain-eval
```

the API server returns:

```
Error from server (Forbidden): secrets is forbidden:
User "system:serviceaccount:kubexplain-eval:s06-limited-sa"
cannot list resource "secrets" in API group "" in the namespace "kubexplain-eval"
```

**Root cause:** the service account does **not have a Role (or ClusterRole) that grants the `list` (and usually `get/watch`) permission on the `secrets` resource** in that namespace. Because the RBAC rule is missing, the API server denies the request, the container exits with a non‑zero code, and the pod ends up in a `CrashLoopBackOff`.

---

### How to fix it

1. **Create a Role that allows secret‑related verbs** in the `kubexplain‑eval` namespace.  
2. **Bind that Role to the service account** with a RoleBinding (or use a pre‑existing ClusterRole if you prefer a cluster‑wide grant).

Below are two common ways to do this.

#### Option A – Namespace‑scoped Role + RoleBinding (recommended)

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: secret‑viewer
  namespace: kubexplain-eval
rules:
- apiGroups: [""]
  resources: ["secrets"]
  verbs: ["get", "list", "watch"]
---
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: s06‑limited‑sa‑secret‑viewer
  namespace: kubexplain-eval
subjects:
- kind: ServiceAccount
  name: s06-limited-sa
  namespace: kubexplain-eval
roleRef:
  kind: Role
  name: secret‑viewer
  apiGroup: rbac.authorization.k8s.io
```

Apply it:

```bash
kubectl apply -f <file‑above>.yaml
```

#### Option B – Use an existing ClusterRole (e.g., `view`)  

If you want to give the service account the standard “read‑only” view permissions across the namespace, you can bind the built‑in `view` ClusterRole:

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: s06‑limited‑sa‑view
  namespace: kubexplain-eval
subjects:
- kind: ServiceAccount
  name: s06-limited-sa
  namespace: kubexplain-eval
roleRef:
  kind: ClusterRole
  name: view
  apiGroup: rbac.authorization.k8s.io
```

Apply it the same way.

---

### Verify the fix

After the Role/RoleBinding is in place, the pod will be able to list secrets:

```bash
kubectl exec -it s06-rbac -n kubexplain-eval -- kubectl get secrets
```

You should see the list of secrets (or an empty list) and the command will exit with status 0.  

If you keep the container’s command `; exit 1`, the pod will still terminate with a non‑zero exit code and go back into `CrashLoopBackOff`.  
Either:

* **Remove the explicit `exit 1`** so the container stays running after a successful `kubectl get`, or  
* **Change the command to a long‑running sleep** (e.g., `kubectl get secrets -n kubexplain-eval && sleep infinity`) if you only need the pod to stay alive for debugging.

---

### Summary

* **Root cause:** missing RBAC permission for the service account to list `secrets` in the `kubexplain‑eval` namespace.  
* **Fix:** create a Role (or use an existing ClusterRole) that grants `get/list/watch` on `secrets` and bind it to `s06‑limited‑sa` with a RoleBinding.  
* **Optional tweak:** adjust the container’s command so it doesn’t deliberately exit with a failure after the `kubectl` call.
