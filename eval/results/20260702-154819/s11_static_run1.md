# s11 | mode=static | run=1 | 10.1s | needs_search=0

**Q:** I cannot create pod 's11-psa-violator' in namespace 'kubexplain-eval-psa'. The API server rejects it with the attached error message. What is the root cause and how do I fix it?

**Expected:** Pod Security Admission 'restricted' rejects root/privilege-escalation; fix securityContext

---

### Root cause  

The namespace **`kubexplain-eval-psa`** is protected by the **PodSecurity admission controller** in **`restricted`** mode (the default when the label `pod-security.kubernetes.io/enforce=restricted` is present).  
When you try to create the pod **`s11-psa-violator`**, the API server checks the pod against the *restricted* policy and finds several violations:

| Policy check | What the pod does | What the policy requires |
|--------------|-------------------|--------------------------|
| **allowPrivilegeEscalation** | Not set (defaults to `true`) | Must be `false` (`securityContext.allowPrivilegeEscalation: false`) |
| **Capabilities** | No capabilities are dropped (defaults to all) | Must drop **all** capabilities (`securityContext.capabilities.drop: ["ALL"]`) |
| **runAsNonRoot** | Not set (defaults to `false`) | Must be `true` (`securityContext.runAsNonRoot: true`) |
| **runAsUser** | Explicitly set to `0` (root) | Must **not** be `0` (any non‑zero UID) |
| **seccompProfile** | Not set (defaults to `unconfined`) | Must be `RuntimeDefault` or a `Localhost` profile (`securityContext.seccompProfile.type: RuntimeDefault`) |

Because the pod does not satisfy **all** of these constraints, the admission controller rejects it with the “Forbidden: violates PodSecurity `restricted:latest`” error.

---

### How to fix it  

You have two general approaches:

1. **Make the pod compliant with the `restricted` policy**  
   Adjust the pod (or its container) spec so that it meets every requirement listed above.

   Example snippet (replace the existing `spec:` section of `s11-podsecurity-restricted.yaml`):

   ```yaml
   apiVersion: v1
   kind: Pod
   metadata:
     name: s11-psa-violator
     namespace: kubexplain-eval-psa
   spec:
     securityContext:
       runAsNonRoot: true          # pod‑level default
       seccompProfile:
         type: RuntimeDefault      # or Localhost with a custom profile
     containers:
     - name: app
       image: <your‑image>
       securityContext:
         allowPrivilegeEscalation: false
         runAsNonRoot: true
         runAsUser: 1000            # any non‑zero UID
         capabilities:
           drop: ["ALL"]
         seccompProfile:
           type: RuntimeDefault
   ```

   *Key points*  
   - Set `allowPrivilegeEscalation: false`.  
   - Drop **all** capabilities (`drop: ["ALL"]`).  
   - Ensure the container runs as a non‑root user (`runAsNonRoot: true` and a UID ≠ 0).  
   - Use a seccomp profile of type `RuntimeDefault` (or a custom `Localhost` profile).  

   After updating the manifest, re‑apply it:

   ```bash
   kubectl apply -f s11-podsecurity-restricted.yaml
   ```

2. **Relax the namespace’s PodSecurity level** (if you really need the pod to run with elevated privileges)  

   - **Option A – downgrade to `baseline`** (still enforces many security checks but allows some privileged features).  
   - **Option B – downgrade to `privileged`** (no restrictions).  

   Change the namespace label accordingly:

   ```bash
   # Baseline (still restricts some things)
   kubectl label namespace kubexplain-eval-psa \
     pod-security.kubernetes.io/enforce=baseline --overwrite

   # Or privileged (no restrictions)
   kubectl label namespace kubexplain-eval-psa \
     pod-security.kubernetes.io/enforce=privileged --overwrite
   ```

   *Note*: If you only need to make an exception for this single pod, you can add **audit** or **warn** labels instead of changing the enforce level, but the pod will still be blocked unless you also add a **`pod-security.kubernetes.io/allow-privileged: "true"`** exemption label on the pod (supported in newer Kubernetes versions).

---

### Which fix to choose?

- **Prefer option 1** (making the pod compliant). It keeps the namespace protected and aligns with best‑practice security hardening.
- Use **option 2** only if the workload truly requires root privileges, extra capabilities, or an unconfined seccomp profile, and you understand the security implications.

---

### Quick checklist after you apply the changes

- `kubectl get pod s11-psa-violator -n kubexplain-eval-psa -o yaml | grep -A5 securityContext` – verify the fields are set as shown above.  
- `kubectl describe pod s11-psa-violator -n kubexplain-eval-psa` – ensure there are no new admission‑controller errors.  
- If the pod still fails, re‑run `kubectl apply` and check the event message; it will list any remaining policy violations.

By aligning the pod’s security context with the `restricted` policy (or adjusting the namespace’s policy level), the creation will succeed.
