# s06 | mode=none | run=2 | 6.4s | needs_search=0

**Q:** An application running as service account 's06-limited-sa' in namespace 'kubexplain-eval' gets an error when listing secrets. The logs are attached. What is the root cause and how do I fix it?

**Expected:** RBAC: role lacks 'list secrets' permission (Forbidden); add the missing rule

---

## Root cause  

The pod **s06‑rbac** runs as the service account **`s06‑limited‑sa`** in the namespace **`kubexplain-eval`**.  
When the container executes  

```bash
kubectl get secrets -n kubexplain-eval
```  

the API server returns:

```
Error from server (Forbidden): secrets is forbidden: User "system:serviceaccount:kubexplain-eval:s06-limited-sa" cannot list resource "secrets" in API group "" in the namespace "kubexplain-eval"
```

That response means the service account **does not have a RBAC rule that grants the `list` (or `get`) verb on the `secrets` resource** in that namespace.  
Because the command exits with a non‑zero status, the container crashes and the pod ends up in **CrashLoopBackOff**.

## How to fix it  

Create a Role (or ClusterRole) that allows reading/listing secrets and bind it to the service account.

### 1. Define a Role that can read secrets in the same namespace  

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

### 3. Apply the manifests  

```bash
kubectl apply -f role-secret-reader.yaml
kubectl apply -f rolebinding-s06-secret-reader.yaml
```

*(Replace the file names with whatever you use.)*

### 4. Verify  

After the Role and RoleBinding are created, the pod will be able to list secrets:

```bash
kubectl exec -it s06-rbac -n kubexplain-eval -- kubectl get secrets
```

You should see the list of secrets and the container will exit cleanly (or you can adjust the container’s command to keep it running).

## Alternative: Cluster‑wide access  

If the application needs to list secrets across multiple namespaces, create a **ClusterRole** with the same rule and a **ClusterRoleBinding** that references the service account. The manifests are identical except `kind: ClusterRole` / `ClusterRoleBinding` and the omission of a namespace in the binding’s `subjects`.

---

**Summary:** The failure is caused by missing RBAC permissions for the service account. Adding a Role (or ClusterRole) that grants `get/list/watch` on `secrets` and binding it to `s06‑limited‑sa` resolves the “Forbidden” error and stops the CrashLoopBackOff.
