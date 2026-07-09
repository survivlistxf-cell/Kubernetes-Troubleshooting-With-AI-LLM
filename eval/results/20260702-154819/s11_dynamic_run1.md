# s11 | mode=dynamic | run=1 | 10.6s | needs_search=0

**Q:** I cannot create pod 's11-psa-violator' in namespace 'kubexplain-eval-psa'. The API server rejects it with the attached error message. What is the root cause and how do I fix it?

**Expected:** Pod Security Admission 'restricted' rejects root/privilege-escalation; fix securityContext

---

**Root cause**

The namespace **`kubexplain-eval-psa`** is protected by the **PodSecurity admission controller** in **`restricted`** mode (the default when a namespace is labeled `pod-security.kubernetes.io/enforce=restricted`).  
When the API server receives the pod definition it checks the pod’s security settings against the *restricted* policy and finds several violations:

| Policy check | What the pod is doing | Required setting |
|--------------|----------------------|------------------|
| `allowPrivilegeEscalation != false` | The container does not set `securityContext.allowPrivilegeEscalation` (or sets it to `true`). | `allowPrivilegeEscalation: false` |
| `unrestricted capabilities` | No capabilities are dropped. | `capabilities.drop: ["ALL"]` |
| `runAsNonRoot != true` | Neither the pod nor the container specifies `runAsNonRoot: true`. | `runAsNonRoot: true` |
| `runAsUser=0` | The container (or pod) sets `runAsUser: 0` (root). | Do **not** set `runAsUser`, or set it to a non‑zero UID. |
| `seccompProfile` missing or wrong | No `securityContext.seccompProfile.type` is defined, or it is not `RuntimeDefault`/`Localhost`. | `seccompProfile.type: RuntimeDefault` (or `Localhost` with a custom profile) |

Because any one of these checks fails, the admission controller rejects the pod with the *Forbidden* error you see.

---

**How to fix it**

You have two general options:

1. **Make the pod compliant with the *restricted* policy** (recommended for production workloads).  
2. **Relax the policy for the namespace** (useful for testing or when you cannot change the pod).

Below are the steps for each option.

---

### 1. Update the pod spec to satisfy the *restricted* policy

Add a `securityContext` at the **container** level (or at the pod level for fields that apply to the whole pod). Example:

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: s11-psa-violator
  namespace: kubexplain-eval-psa
spec:
  containers:
  - name: app
    image: <your‑image>
    # ... your existing container spec ...
    securityContext:
      allowPrivilegeEscalation: false
      runAsNonRoot: true          # ensures the container runs as non‑root
      # Do NOT set runAsUser: 0; either omit it or set a non‑zero UID
      capabilities:
        drop: ["ALL"]             # drop all Linux capabilities
      seccompProfile:
        type: RuntimeDefault      # or type: Localhost with a profile path
```

If you prefer to set some fields at the pod level (they apply to every container), you can also add:

```yaml
spec:
  securityContext:
    runAsNonRoot: true
    # runAsUser: 1000   # any non‑zero UID, optional
    seccompProfile:
      type: RuntimeDefault
```

After updating the manifest, re‑apply:

```bash
kubectl apply -f s11-podsecurity-restricted.yaml
```

The pod should now be admitted.

---

### 2. Relax the PodSecurity level for the namespace (temporary / testing)

If you cannot modify the pod (e.g., a third‑party workload) and you only need to run it in this isolated namespace, you can lower the enforcement level:

```bash
# Set the namespace to "baseline" (still enforces many checks but is less strict)
kubectl label namespace kubexplain-eval-psa \
  pod-security.kubernetes.io/enforce=baseline \
  --overwrite

# Or completely disable enforcement (use with caution)
kubectl label namespace kubexplain-eval-psa \
  pod-security.kubernetes.io/enforce=privileged \
  --overwrite
```

After relabeling, the same `kubectl apply` will succeed because the *restricted* checks are no longer applied.

> **Caution:** Lowering the policy reduces the security guarantees for any pod running in that namespace. Use it only when you understand the risk and preferably only in a non‑production environment.

---

### Quick checklist to verify compliance

1. **`allowPrivilegeEscalation`** – must be `false`.  
2. **Capabilities** – `drop: ["ALL"]` (no `add` entries).  
3. **`runAsNonRoot`** – `true` (or omit `runAsUser` and ensure the image’s default user is non‑root).  
4. **`runAsUser`** – must **not** be `0`.  
5. **`seccompProfile.type`** – `RuntimeDefault` or `Localhost`.

Running `kubectl explain pod.spec.containers.securityContext` can help you see the exact fields.

---

**Summary**

The pod is rejected because the namespace enforces the *restricted* PodSecurity policy, and the pod’s security context does not meet the required settings. Fix the pod by adding the appropriate `securityContext` fields (or, if appropriate, lower the namespace’s enforcement level). Once the pod complies, the API server will accept it.
