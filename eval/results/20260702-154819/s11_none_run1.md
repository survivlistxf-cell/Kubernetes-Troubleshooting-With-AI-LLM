# s11 | mode=none | run=1 | 8.7s | needs_search=0

**Q:** I cannot create pod 's11-psa-violator' in namespace 'kubexplain-eval-psa'. The API server rejects it with the attached error message. What is the root cause and how do I fix it?

**Expected:** Pod Security Admission 'restricted' rejects root/privilege-escalation; fix securityContext

---

### Root cause  

The namespace **`kubexplain-eval-psa`** is protected by the **PodSecurity admission controller** in **`restricted`** mode (the default “restricted” policy).  
When you try to create the pod **`s11-psa-violator`**, the API server checks the pod against that policy and finds several violations:

| Policy check | What the pod currently does | What the policy requires |
|--------------|----------------------------|--------------------------|
| **allowPrivilegeEscalation** | Not set (defaults to `true`) | Must be `false` (`securityContext.allowPrivilegeEscalation: false`) |
| **Capabilities** | No drop list (so all capabilities are allowed) | Must drop **all** capabilities (`securityContext.capabilities.drop: ["ALL"]`) |
| **runAsNonRoot** | Not set (or set to `false`) | Must be `true` (`securityContext.runAsNonRoot: true`) |
| **runAsUser** | Set to `0` (root) | Must be a non‑zero UID (e.g. `1000`) |
| **seccompProfile** | Not set (defaults to `Unconfined`) | Must be `RuntimeDefault` or a `Localhost` profile (`securityContext.seccompProfile.type: RuntimeDefault`) |

Because any one of these mismatches makes the pod non‑compliant, the admission controller rejects the creation request with the *Forbidden* error you see.

---

### How to fix it  

You have two broad options:

1. **Make the pod compliant with the `restricted` policy** (recommended).  
2. **Relax the policy for the namespace** (only if you really need the privileged behavior).

Below are the steps for each approach.

---

#### 1. Adjust the pod spec to satisfy the `restricted` policy  

Add or modify the `securityContext` fields for the container (and optionally the pod) so that all required checks pass.

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
    # ---- security settings required by the restricted policy ----
    securityContext:
      allowPrivilegeEscalation: false          # disallow escalation
      capabilities:
        drop: ["ALL"]                           # drop every capability
      runAsNonRoot: true                       # must run as non‑root
      runAsUser: 1000                          # any non‑zero UID
      seccompProfile:
        type: RuntimeDefault                   # use the default seccomp profile
    # -------------------------------------------------------------
```

Key points:

- **`allowPrivilegeEscalation: false`** – prevents the container from gaining extra privileges after start‑up.  
- **`capabilities.drop: ["ALL"]`** – removes all Linux capabilities; you can add back only those you truly need (but then you must also add `add:` entries, which may still be disallowed by `restricted`).  
- **`runAsNonRoot: true`** together with a **non‑zero `runAsUser`** – ensures the process does not run as UID 0.  
- **`seccompProfile.type: RuntimeDefault`** – uses the cluster‑wide default seccomp profile, which is allowed by the policy.

After updating the manifest, re‑apply it (`kubectl apply -f …`). The pod should be admitted successfully.

---

#### 2. Change the namespace’s PodSecurity level (if you really need the original, more‑privileged pod)

If the pod truly requires privileged settings that cannot be reconciled with `restricted`, you can lower the enforcement level for the namespace:

- **`baseline`** – still restrictive but allows some privileged features (e.g., `allowPrivilegeEscalation` can be true, capabilities can be added).  
- **`privileged`** – disables most checks; essentially no restrictions.

You would modify the namespace’s PodSecurity labels, for example:

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: kubexplain-eval-psa
  labels:
    pod-security.kubernetes.io/enforce: baseline   # or "privileged"
    pod-security.kubernetes.io/enherit: baseline
    pod-security.kubernetes.io/audit: baseline
```

Apply the updated namespace definition, then retry creating the pod.  

**Caution:** Lowering the policy reduces the security guarantees for every workload in that namespace. Use it only when you control the namespace and understand the risk.

---

### Summary  

- The pod is rejected because the **`restricted`** PodSecurity policy is enforced in the target namespace and the pod’s security settings violate several mandatory checks.  
- **Fix it** by adding the required `securityContext` fields (`allowPrivilegeEscalation: false`, drop all capabilities, run as non‑root with a non‑zero UID, and set a `RuntimeDefault` seccomp profile).  
- **Alternative**: relax the namespace’s PodSecurity level (to `baseline` or `privileged`) if the pod truly needs those privileged settings, but this weakens security for the whole namespace.  

Apply the appropriate changes and the pod should be created without the “Forbidden” error.
