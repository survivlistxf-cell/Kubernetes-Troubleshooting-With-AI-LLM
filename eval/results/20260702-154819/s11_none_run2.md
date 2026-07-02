# s11 | mode=none | run=2 | 6.8s | needs_search=0

**Q:** I cannot create pod 's11-psa-violator' in namespace 'kubexplain-eval-psa'. The API server rejects it with the attached error message. What is the root cause and how do I fix it?

**Expected:** Pod Security Admission 'restricted' rejects root/privilege-escalation; fix securityContext

---

### Why the pod is rejected  

Your namespace **kubexplain‑eval‑psa** is bound to the **PodSecurity** admission controller in **restricted** mode (the default when a namespace has the label `pod-security.kubernetes.io/enforce=restricted`).  
When a pod is created the controller checks the pod’s security settings against the “restricted” policy.  

The error message lists every rule that the pod **s11‑psa‑violator** violates:

| Policy rule | What the pod is doing | Required setting |
|-------------|----------------------|------------------|
| `allowPrivilegeEscalation != false` | The container does not set `securityContext.allowPrivilegeEscalation` (or leaves it at the default `true`). | `allowPrivilegeEscalation: false` |
| `unrestricted capabilities` | No capabilities are dropped. | `securityContext.capabilities.drop: ["ALL"]` |
| `runAsNonRoot != true` | The pod/container does not request to run as a non‑root user. | `runAsNonRoot: true` (or omit `runAsUser` and let the image run as non‑root) |
| `runAsUser=0` | The container explicitly sets `runAsUser: 0`. | Do **not** set `runAsUser`, or set it to a non‑zero UID. |
| `seccompProfile` missing or wrong | No `seccompProfile` is defined. | `securityContext.seccompProfile.type: RuntimeDefault` (or `Localhost` with a custom profile) |

Because any one of those mismatches is enough to block the pod, the API server returns **Forbidden**.

### How to fix it  

You have two broad options:

1. **Make the pod compliant with the “restricted” policy**  
   Edit the pod (or Deployment, etc.) manifest and add the required security context fields.

   Example snippet for the container named **app**:

   ```yaml
   spec:
     containers:
     - name: app
       image: <your‑image>
       securityContext:
         allowPrivilegeEscalation: false
         capabilities:
           drop: ["ALL"]
         runAsNonRoot: true
         # Do NOT set runAsUser, or set it to a non‑zero UID
         seccompProfile:
           type: RuntimeDefault
   ```

   If the pod spec already contains a `securityContext` at the pod level, you can put the same fields there; they will be inherited by the container unless overridden.

2. **Relax the namespace’s PodSecurity level**  
   If you cannot or do not want to modify the pod, you can change the namespace’s enforcement level to a less strict profile (e.g., **baseline** or **privileged**) or remove the enforcement label entirely.

   - To downgrade to **baseline** (still provides some protection but allows the settings you are using):

     ```bash
     kubectl label namespace kubexplain-eval-psa \
       pod-security.kubernetes.io/enforce=baseline --overwrite
     ```

   - To completely disable enforcement (not recommended for production clusters):

     ```bash
     kubectl label namespace kubexplain-eval-psa \
       pod-security.kubernetes.io/enforce-   # removes the label
     ```

   After changing the label, you can re‑apply the pod manifest.

### Which approach to choose?

- **Security‑first environments** (e.g., production, shared clusters) should keep the **restricted** policy and adjust the pod to meet the requirements. This ensures the workload follows best‑practice hardening.
- **Testing, learning, or isolated environments** where you control the whole cluster may temporarily relax the policy to speed up experimentation, but you should eventually bring the workload into compliance.

### Quick checklist for compliance

- `allowPrivilegeEscalation: false`
- `capabilities.drop: ["ALL"]`
- `runAsNonRoot: true` **and** no explicit `runAsUser: 0`
- `seccompProfile.type: RuntimeDefault` (or a custom `Localhost` profile)

Add those to every container (or at the pod level) and the pod will be accepted in a **restricted** namespace.
