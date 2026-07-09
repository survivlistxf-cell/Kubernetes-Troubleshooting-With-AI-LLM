# s11 | mode=static | run=3 | 10.1s | needs_search=0

**Q:** I cannot create pod 's11-psa-violator' in namespace 'kubexplain-eval-psa'. The API server rejects it with the attached error message. What is the root cause and how do I fix it?

**Expected:** Pod Security Admission 'restricted' rejects root/privilege-escalation; fix securityContext

---

### Why the pod is rejected  

Your namespace **kubexplain‑eval‑psa** is protected by the **PodSecurity** admission controller in **`restricted`** mode (the default when you enable the feature).  
When a pod is created the controller checks the pod’s security settings against the *restricted* policy. The pod **s11‑psa‑violator** fails several of those checks, which is why the API server returns:

```
violates PodSecurity "restricted:latest":
  allowPrivilegeEscalation != false
  unrestricted capabilities
  runAsNonRoot != true
  runAsUser=0
  seccompProfile not set to RuntimeDefault or Localhost
```

In other words, the pod’s **securityContext** does not meet the strict requirements of the *restricted* policy.

---

### How to fix it  

You have two general options:

1. **Make the pod compliant with the `restricted` policy**  
2. **Relax the policy for the namespace (or just for this pod)**  

Below are the concrete steps for each approach.

---

#### 1️⃣ Make the pod itself compliant  

Edit the pod manifest (`s11-podsecurity-restricted.yaml`) and add/adjust the security settings for the container (or the pod) as follows:

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: s11-psa-violator
  namespace: kubexplain-eval-psa
spec:
  securityContext:
    # Ensure the pod runs as a non‑root user (any UID > 0)
    runAsNonRoot: true
    # Optional: you can also set a specific non‑zero UID
    # runAsUser: 1000

  containers:
  - name: app
    image: <your‑image>
    securityContext:
      # Must be false for restricted
      allowPrivilegeEscalation: false
      # Drop all Linux capabilities
      capabilities:
        drop: ["ALL"]
      # Must run as non‑root (mirrors pod‑level setting)
      runAsNonRoot: true
      # Do NOT set runAsUser: 0; use a non‑zero UID or omit it
      # runAsUser: 1000
      # Use the default seccomp profile
      seccompProfile:
        type: RuntimeDefault   # or Localhost if you provide a custom profile
```

Key points:

| Requirement | What to set |
|-------------|-------------|
| `allowPrivilegeEscalation` | `false` |
| `capabilities` | `drop: ["ALL"]` |
| `runAsNonRoot` | `true` (both pod and container) |
| `runAsUser` | **do not** set to `0`; either omit or set a UID > 0 |
| `seccompProfile.type` | `RuntimeDefault` **or** `Localhost` (with a custom profile) |

After updating the manifest, re‑apply:

```bash
kubectl apply -f s11-podsecurity-restricted.yaml
```

If the pod still fails, double‑check that no other containers in the pod violate the same rules.

---

#### 2️⃣ Relax the PodSecurity level for the namespace  

If you want to keep the pod as‑is (e.g., for testing) you can lower the enforcement level for the namespace or add an exemption label.

**Option A – Change the namespace to `baseline` (still restrictive but less strict):**

```bash
kubectl label namespace kubexplain-eval-psa \
  pod-security.kubernetes.io/enforce=baseline \
  pod-security.kubernetes.io/enforce-version=latest \
  --overwrite
```

**Option B – Disable enforcement for this namespace (use with caution):**

```bash
kubectl label namespace kubexplain-eval-psa \
  pod-security.kubernetes.io/enforce=privileged \
  pod-security.kubernetes.io/enforce-version=latest \
  --overwrite
```

**Option C – Add an exemption for just this pod (recommended for a single test pod):**

```bash
kubectl label pod s11-psa-violator \
  pod-security.kubernetes.io/audit=privileged \
  pod-security.kubernetes.io/audit-version=latest \
  pod-security.kubernetes.io/warn=privileged \
  pod-security.kubernetes.io/warn-version=latest \
  --overwrite
```

> **Caution:** Lowering the policy or adding exemptions reduces the security guarantees for everything running in that namespace. Use the most restrictive setting that still allows your workload.

---

### Quick checklist to verify the fix  

1. **Describe the pod** after creation:

   ```bash
   kubectl describe pod s11-psa-violator -n kubexplain-eval-psa
   ```

   Look for `Security Context` fields and confirm they match the table above.

2. **Check the namespace’s PodSecurity labels**:

   ```bash
   kubectl get namespace kubexplain-eval-psa -o yaml | grep pod-security.kubernetes.io
   ```

3. **Confirm the pod is running**:

   ```bash
   kubectl get pod s11-psa-violator -n kubexplain-eval-psa
   ```

If the pod reaches `Running` (or `Completed` for a job) without a `Forbidden` error, the issue is resolved.
