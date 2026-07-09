# s06 | mode=dynamic | run=2 | 7.0s | needs_search=0

**Q:** An application running as service account 's06-limited-sa' in namespace 'kubexplain-eval' gets an error when listing secrets. The logs are attached. What is the root cause and how do I fix it?

**Expected:** RBAC: role lacks 'list secrets' permission (Forbidden); add the missing rule

---

**Root cause**

The pod is running as the ServiceAccount **`s06‑limited‑sa`** in the **`kubexplain‑eval`** namespace.  
When the container executes `kubectl get secrets -n kubexplain-eval` the API server returns:

```
Error from server (Forbidden): secrets is forbidden:
User "system:serviceaccount:kubexplain-eval:s06-limited-sa"
cannot list resource "secrets" in API group "" in the namespace "kubexplain-eval"
```

Kubernetes RBAC gives every ServiceAccount only the default read‑only discovery permissions. No Role or ClusterRole that allows `list` (or `get`) on the `secrets` resource has been bound to this ServiceAccount, so the request is denied. The container exits with a non‑zero code, causing the CrashLoopBackOff you see.

**How to fix it**

Create a Role that grants the needed secret‑reading verbs and bind that Role to the ServiceAccount.

```yaml
# 1️⃣ Role that can read secrets in the namespace
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

```yaml
# 2️⃣ RoleBinding that attaches the Role to the ServiceAccount
apiVersion: rbac.authorization.k8s.io/v1
kind: RoleBinding
metadata:
  name: s06-limited-sa-secret-reader
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

Apply the manifests:

```bash
kubectl apply -f role-secret-reader.yaml
kubectl apply -f rolebinding-secret-reader.yaml
```

**Result**

After the Role and RoleBinding are in place, the ServiceAccount `s06-limited-sa` will be authorized to list (and get/watch) Secrets in `kubexplain-eval`. The `kubectl get secrets` command will succeed, the container will exit with code 0, and the pod will stop crashing.

**Optional variations**

- If the pod needs to read secrets in multiple namespaces, create a **ClusterRole** with the same rule and a **ClusterRoleBinding** (or a separate RoleBinding in each namespace).
- If you only need to read a specific secret, you can narrow the rule with `resourceNames: ["my-secret"]` to follow the principle of least privilege.
