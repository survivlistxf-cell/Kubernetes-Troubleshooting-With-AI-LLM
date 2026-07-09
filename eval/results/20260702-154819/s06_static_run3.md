# s06 | mode=static | run=3 | 8.0s | needs_search=0

**Q:** An application running as service account 's06-limited-sa' in namespace 'kubexplain-eval' gets an error when listing secrets. The logs are attached. What is the root cause and how do I fix it?

**Expected:** RBAC: role lacks 'list secrets' permission (Forbidden); add the missing rule

---

**Root cause**

The pod runs with the service account **`s06‑limited‑sa`** in the namespace **`kubexplain-eval`**.  
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

This means that the service account does **not have a RBAC rule that allows it to `list` (or `get`) `secrets`** in that namespace. Because the command exits with a non‑zero status, the container repeatedly crashes and the pod goes into a back‑off restart loop.

**How to fix it**

Create a Role (or ClusterRole) that grants the needed permissions on `secrets`, and bind that Role to the service account with a RoleBinding (or ClusterRoleBinding).

---

### 1. Define a Role that can read secrets in the namespace

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: secret-reader
  namespace: kubexplain-eval
rules:
- apiGroups: [""]
  resources: ["secrets"]
  verbs: ["get", "list", "watch"]
```

### 2. Bind the Role to the service account

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: s06-secret-reader
  namespace: kubexplain-eval
subjects:
- kind: ServiceAccount
  name: s06-limited-sa
  namespace: kubexplain-eval
roleRef:
  kind: Role
  name: secret-reader
  apiGroup: rbac.authorization.k8s.io
```

Apply both manifests:

```bash
kubectl apply -f role-secret-reader.yaml
kubectl apply -f rolebinding-s06-secret-reader.yaml
```

*(If you need the service account to read secrets in **all** namespaces, replace the `Role` with a `ClusterRole` and use a `ClusterRoleBinding` instead.)*

### 3. Verify the permission

```bash
kubectl auth can-i list secrets --as=system:serviceaccount:kubexplain-eval:s06-limited-sa -n kubexplain-eval
```

The command should now return `yes`.

### 4. Observe the pod

After the RBAC objects are created, the existing pod will restart (or you can delete it to let the Deployment/ReplicaSet recreate it). The `kubectl get secrets` command should succeed, and the container will exit cleanly instead of entering the back‑off loop.

---

**Summary**

- The failure is caused by missing RBAC permission for the service account to list secrets.
- Adding a `Role` (or `ClusterRole`) with `list/get/watch` on the `secrets` resource and binding it to `s06‑limited‑sa` resolves the issue. Once the binding is in place, the pod can list secrets and will stop crashing.
