# s06 | mode=static | run=2 | 7.8s | needs_search=0

**Q:** An application running as service account 's06-limited-sa' in namespace 'kubexplain-eval' gets an error when listing secrets. The logs are attached. What is the root cause and how do I fix it?

**Expected:** RBAC: role lacks 'list secrets' permission (Forbidden); add the missing rule

---

## Root cause  

The pod runs as **service account `s06‑limited‑sa`** in the namespace **`kubexplain‑eval`**.  
When the container executes  

```bash
kubectl get secrets -n kubexplain-eval
```  

the API server returns:

```
Error from server (Forbidden): secrets is forbidden: User "system:serviceaccount:kubexplain-eval:s06-limited-sa" cannot list resource "secrets" in API group "" in the namespace "kubexplain-eval"
```

This means the service account **does not have a Role/ClusterRole that grants the `list` (or `get`) verb on the `secrets` resource** in that namespace. The RBAC check blocks the request, causing the command to exit with code 1 and the pod to restart.

## How to fix it  

Create a Role that allows reading secrets in the `kubexplain‑eval` namespace and bind that Role to the service account.

### 1. Define a Role (or ClusterRole)  

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

*If you need the service account to read secrets in **all** namespaces, replace `Role` with `ClusterRole` and omit the `namespace` field.*

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
  kind: Role          # use "ClusterRole" if you created a ClusterRole
  name: secret-reader
  apiGroup: rbac.authorization.k8s.io
```

### 3. Apply the manifests  

```bash
kubectl apply -f role.yaml
kubectl apply -f rolebinding.yaml
```

### 4. Verify  

After the Role/RoleBinding are in place, the pod’s command should succeed:

```bash
kubectl exec -it s06-rbac -n kubexplain-eval -- kubectl get secrets -n kubexplain-eval
```

You should see the list of secrets (or an empty list) instead of a Forbidden error. The container will then exit with code 0, stopping the restart loop.

## Summary  

- **Problem:** `s06-limited-sa` lacks permission to list `secrets` → API server returns Forbidden.  
- **Fix:** Grant the needed permission via a `Role` (or `ClusterRole`) and a corresponding `RoleBinding` (or `ClusterRoleBinding`).  
- **Result:** The pod can list secrets, the command exits cleanly, and the pod stops crashing.
