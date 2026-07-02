# s11 | mode=none | run=3 | 8.1s | needs_search=0

**Q:** I cannot create pod 's11-psa-violator' in namespace 'kubexplain-eval-psa'. The API server rejects it with the attached error message. What is the root cause and how do I fix it?

**Expected:** Pod Security Admission 'restricted' rejects root/privilege-escalation; fix securityContext

---

## Root cause  

The namespace **kubexplain‑eval‑psa** is under the **PodSecurity** admission controller in **`restricted`** mode (enforced).  
When the API server receives the pod definition it checks the pod‑ and container‑level `securityContext` against the rules of the *restricted* profile.  

Your pod **s11‑psa‑violator** fails all of the required checks:

| Required check (restricted profile) | What the pod currently has | Why it is rejected |
|-------------------------------------|----------------------------|--------------------|
| `allowPrivilegeEscalation` must be **false** | not set (defaults to `true`) | “allowPrivilegeEscalation != false” |
| All Linux capabilities must be dropped (`capabilities.drop: ["ALL"]`) | not set (inherits default capabilities) | “unrestricted capabilities” |
| `runAsNonRoot` must be **true** | not set (defaults to `false`) | “runAsNonRoot != true” |
| `runAsUser` must not be `0` (root) | explicitly set to `0` | “runAsUser=0” |
| `seccompProfile.type` must be **RuntimeDefault** or **Localhost** | not set (defaults to `Unconfined`) | “seccompProfile … must set … to RuntimeDefault or Localhost” |

Because the pod violates every rule of the *restricted* profile, the admission controller returns **Forbidden** and the pod is never created.

---

## How to fix it  

You have two general options:

### 1. Make the pod compliant with the *restricted* profile  

Add the required fields to the pod (or container) `securityContext` so that all checks pass.

```yaml
apiVersion: v1
kind: Pod
metadata:
  name: s11-psa-violator
  namespace: kubexplain-eval-psa
spec:
  securityContext:
    runAsNonRoot: true               # pod‑level default
    seccompProfile:
      type: RuntimeDefault           # or Localhost with a profile file
  containers:
  - name: app
    image: <your‑image>
    securityContext:
      runAsUser: 1000                 # any non‑zero UID
      allowPrivilegeEscalation: false
      capabilities:
        drop: ["ALL"]
      # optional: you can repeat runAsNonRoot here, but pod‑level already applies
```

Apply the corrected manifest:

```bash
kubectl apply -f s11-podsecurity-restricted.yaml
```

### 2. Relax the namespace’s PodSecurity level (if you really need the original pod spec)

If the workload truly requires privileged settings, you can lower the enforcement for the namespace:

```bash
# Change the namespace to the less‑strict "baseline" profile (or "privileged")
kubectl label namespace kubexplain-eval-psa \
  pod-security.kubernetes.io/enforce=baseline \
  pod-security.kubernetes.io/enforce-version=latest --overwrite
```

or completely disable enforcement for this namespace:

```bash
kubectl label namespace kubexplain-eval-psa \
  pod-security.kubernetes.io/enforce- - pod-security.kubernetes.io/audit- - pod-security.kubernetes.io/warn- --overwrite
```

> **Caution:** Relaxing the policy reduces the security guarantees for every pod created in that namespace. Prefer fixing the pod spec whenever possible.

---

### Quick checklist for a *restricted*‑compatible pod

- **Pod‑level `securityContext`**  
  - `runAsNonRoot: true`  
  - `seccompProfile.type: RuntimeDefault` (or `Localhost`)  

- **Container‑level `securityContext`** (for each container)  
  - `runAsUser` set to a non‑zero UID (e.g., `1000`)  
  - `allowPrivilegeEscalation: false`  
  - `capabilities.drop: ["ALL"]`  

If you add all of the above, the pod will be accepted by the `restricted` profile and the creation will succeed.
