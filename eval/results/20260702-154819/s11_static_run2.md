# s11 | mode=static | run=2 | 9.0s | needs_search=0

**Q:** I cannot create pod 's11-psa-violator' in namespace 'kubexplain-eval-psa'. The API server rejects it with the attached error message. What is the root cause and how do I fix it?

**Expected:** Pod Security Admission 'restricted' rejects root/privilege-escalation; fix securityContext

---

### Root cause  

The namespace **`kubexplain-eval-psa`** is protected by the **PodSecurity admission controller** in **`restricted`** mode (the default when a namespace has the label `pod-security.kubernetes.io/enforce=restricted`).  

When you try to create the pod **`s11-psa-violator`**, the API server checks the pod against the *restricted* policy and finds several violations:

| Policy check | What the pod is doing | What the policy requires |
|--------------|----------------------|--------------------------|
| **allowPrivilegeEscalation** | Not set (defaults to `true`) | Must be `false` (`securityContext.allowPrivilegeEscalation: false`) |
| **Capabilities** | No capabilities are dropped (defaults to all allowed) | Must drop **all** capabilities (`securityContext.capabilities.drop: ["ALL"]`) |
| **runAsNonRoot** | Not set (defaults to `false`) | Must be `true` (`securityContext.runAsNonRoot: true`) |
| **runAsUser** | Explicitly set to `0` (root) | Must not be `0`; any non‑zero UID (e.g., `1000`) |
| **seccompProfile** | Not set (defaults to `Unconfined`) | Must be `RuntimeDefault` or a custom `Localhost` profile (`securityContext.seccompProfile.type: RuntimeDefault`) |

Because any one of these mismatches is enough to reject the pod, the API server returns the **Forbidden** error you see.

### How to fix it  

You have two general approaches:

1. **Make the pod compliant with the `restricted` policy** (recommended if you want to keep the namespace secured).  
2. **Relax the policy for the namespace** (only if you deliberately need a less‑strict environment).

#### 1. Adjust the pod spec to satisfy the `restricted` policy  

Add or modify the `securityContext` fields for the pod **or** for the container named `app`:

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
    # Existing container spec …
    securityContext:
      allowPrivilegeEscalation: false
      capabilities:
        drop: ["ALL"]
      runAsNonRoot: true          # ensures the container runs as non‑root
      runAsUser: 1000             # any non‑zero UID; must match the image's user if it expects a specific UID
      seccompProfile:
        type: RuntimeDefault      # or type: Localhost with a path to a custom profile
```

You can place the `securityContext` at the **pod level** (applies to all containers) or at the **container level** (as shown). Ensure that the image you use can run as the non‑root UID you pick; otherwise adjust the UID accordingly.

After updating the manifest, re‑apply it:

```bash
kubectl apply -f s11-podsecurity-restricted.yaml
```

#### 2. Change the namespace’s PodSecurity level (if you truly need a less‑restricted pod)

If you cannot make the pod compliant (e.g., it needs privileged capabilities), you can lower the enforcement level for the namespace:

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

After changing the label, the same pod creation request will be allowed, provided it meets the new level’s requirements (or none, for `privileged`).

### Summary  

- The pod is rejected because the **`restricted`** PodSecurity policy is enforced in the target namespace.  
- The pod lacks required security settings: `allowPrivilegeEscalation: false`, drop all capabilities, run as non‑root, non‑zero UID, and a `RuntimeDefault` seccomp profile.  
- **Fix** by adding the appropriate `securityContext` fields to the pod/container spec, **or** relax the namespace’s PodSecurity level by relabeling the namespace.  

Choose the approach that aligns with your security posture. If you keep the `restricted` level, the manifest changes above will make the pod pass admission.
