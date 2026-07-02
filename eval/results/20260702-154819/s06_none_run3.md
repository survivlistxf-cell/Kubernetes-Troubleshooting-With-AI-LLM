# s06 | mode=none | run=3 | 6.9s | needs_search=0

**Q:** An application running as service account 's06-limited-sa' in namespace 'kubexplain-eval' gets an error when listing secrets. The logs are attached. What is the root cause and how do I fix it?

**Expected:** RBAC: role lacks 'list secrets' permission (Forbidden); add the missing rule

---

### Root cause  
The pod runs with the service account **`s06‑limited‑sa`** in the namespace **`kubexplain-eval`**.  
When it executes  

```bash
kubectl get secrets -n kubexplain-eval
```  

the API server returns:

```
Error from server (Forbidden): secrets is forbidden: User "system:serviceaccount:kubexplain-eval:s06-limited-sa" cannot list resource "secrets" in API group "" in the namespace "kubexplain-eval"
```

That response means the service account **does not have a Role/ClusterRole that grants the `list` (or `get`) verb on the `secrets` resource** in that namespace. Because the command exits with a non‑zero status, the container repeatedly crashes, leading to the `CrashLoopBackOff` you see.

### How to fix it  

1. **Create a Role that allows reading secrets in the target namespace.**  
2. **Bind that Role to the service account with a RoleBinding** (or a ClusterRole/ClusterRoleBinding if you need the permission across namespaces).

#### Example manifests

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: s06-secret-reader
  namespace: kubexplain-eval
rules:
- apiGroups: [""]
  resources: ["secrets"]
  verbs: ["get", "list"]
```

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: s06-secret-reader-binding
  namespace: kubexplain-eval
subjects:
- kind: ServiceAccount
  name: s06-limited-sa
  namespace: kubexplain-eval
roleRef:
  kind: Role
  name: s06-secret-reader
  apiGroup: rbac.authorization.k8s.io
```

Apply them:

```bash
kubectl apply -f role.yaml
kubectl apply -f rolebinding.yaml
```

*(If you already have a Role/ClusterRole that should cover this, verify that it includes `secrets` in the `resources` list and that a binding exists for `s06-limited-sa`.)*

3. **Verify the permission**  

```bash
kubectl auth can-i list secrets -n kubexplain-eval --as=system:serviceaccount:kubexplain-eval:s06-limited-sa
```

The command should now return `yes`.

4. **Restart the pod (or let it be recreated)**  

The pod will run the `kubectl get secrets` command successfully, exit with status 0, and the `CrashLoopBackOff` will disappear. If you want the container to stay alive after the check, replace the `exit 1` with something like `sleep infinity` or a proper entrypoint.

### Summary  

- **Root cause:** Missing RBAC permission for the service account to list `secrets`.  
- **Fix:** Create a `Role` (or `ClusterRole`) granting `get/list` on `secrets` in the `kubexplain-eval` namespace and bind it to `s06-limited-sa` with a `RoleBinding`. After the binding is applied, the pod will be able to list secrets and the crash loop will stop.
