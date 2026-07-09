# s06 | mode=dynamic | run=3 | 6.0s | needs_search=0

**Q:** An application running as service account 's06-limited-sa' in namespace 'kubexplain-eval' gets an error when listing secrets. The logs are attached. What is the root cause and how do I fix it?

**Expected:** RBAC: role lacks 'list secrets' permission (Forbidden); add the missing rule

---

**Root cause**

The pod is running as the ServiceAccount **`s06‑limited‑sa`** in the **`kubexplain‑eval`** namespace.  
When the container executes `kubectl get secrets -n kubexplain‑eval` the API server returns:

```
Error from server (Forbidden): secrets is forbidden:
User "system:serviceaccount:kubexplain-eval:s06-limited-sa"
cannot list resource "secrets" in API group "" in the namespace "kubexplain-eval"
```

This means the ServiceAccount does **not have a Role/ClusterRole that grants the `list` (or `get/watch`) verb on the `secrets` resource** in that namespace. By default a ServiceAccount only has the generic discovery permissions; it cannot read Secrets unless you explicitly bind a role.

---

**How to fix it**

Create a **Role** (namespace‑scoped) that allows the needed secret operations and bind it to the ServiceAccount with a **RoleBinding**.  
If you want the ServiceAccount to be able to read *any* secret in the namespace, the role can be:

```yaml
apiVersion: rbac.authorization.k8s.io/v1
kind: Role
metadata:
  name: s06-secret-reader
  namespace: kubexplain-eval
rules:
- apiGroups: [""]
  resources: ["secrets"]
  verbs: ["get", "list", "watch"]
```

Bind the role to the ServiceAccount:

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

Apply the manifests:

```bash
kubectl apply -f role-secret-reader.yaml
kubectl apply -f rolebinding-secret-reader.yaml
```

*If you need the ServiceAccount to read secrets across *all* namespaces*, replace the `Role`/`RoleBinding` with a `ClusterRole`/`ClusterRoleBinding` (same rules, but `ClusterRole` and `ClusterRoleBinding` objects).

---

**Verification**

After the RoleBinding is in place, the pod will be able to list secrets:

```bash
kubectl exec -it s06-rbac -n kubexplain-eval -- kubectl get secrets
```

You should see the list of Secrets without a “Forbidden” error. If you still see a denial, double‑check:

1. The ServiceAccount name and namespace match the binding.
2. No other `Deny` policies (e.g., OPA, Gatekeeper) are overriding the RBAC grant.
3. The pod is using the same ServiceAccount (the `describe` output already shows it does).

Once the binding is correct, the CrashLoopBackOff will stop because the command will succeed.
