# s09 | mode=dynamic | run=3 | 12.5s | needs_search=0

**Q:** My pod 's09-bad-readiness' in namespace 'kubexplain-eval' is running but receives no traffic from service 's09-web' (its endpoints are empty). What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** readiness probe fails (wrong path) so the pod is out of endpoints; fix the probe

---

**Why the Service `s09‑web` has an empty Endpoints list for pod `s09‑bad‑readiness`**

When a Service shows no endpoints, the most common reason is that the Service’s selector does **not** match any **Ready** Pods.  
Below are the typical causes, ordered by how often they appear in practice, together with the quick checks you can run (once you have cluster access) and the fix for each one.

---

## 1️⃣ Readiness probe keeps the pod in *NotReady* state  
**Probability ≈ 45 %** – a pod that is Running but fails its readiness probe is excluded from the Service’s endpoint list.

**How to verify (when you can run commands)**  

```bash
kubectl describe pod s09-bad-readiness -n kubexplain-eval
```

* Look for the **Ready** condition (`Ready=False`) and the **Readiness probe** section.  
* Check the `Last Probe Time`, `Message`, and `Reason` – they will tell you why the probe is failing (e.g., connection refused, timeout, wrong path).

**How to fix**

* **Correct the probe** – adjust `httpGet`, `tcpSocket`, or `exec` parameters so the container actually responds.
* If the probe is unnecessary, you can temporarily remove it or set `initialDelaySeconds` / `periodSeconds` to give the app more time.
* After updating the pod spec (e.g., `kubectl apply -f pod.yaml`), the pod will become Ready and the endpoint will appear.

---

## 2️⃣ Service selector does not match the pod’s labels  
**Probability ≈ 30 %** – a typo or mismatched key/value means the Service never selects the pod.

**How to verify**

```bash
# Show the Service selector
kubectl get svc s09-web -n kubexplain-eval -o jsonpath='{.spec.selector}'

# List pods that match that selector
kubectl get pods -n kubexplain-eval -l <key>=<value>
```

*If the pod does not appear in the list, the selector is wrong.*

**How to fix**

* Edit the Service (or the pod) so the selector keys/values line up, e.g.:

```yaml
# Service
spec:
  selector:
    app: s09-web   # <-- must match a label on the pod
```

*or* add the missing label to the pod:

```bash
kubectl label pod s09-bad-readiness -n kubexplain-eval app=s09-web
```

After the label change, the endpoint will be created automatically.

---

## 3️⃣ TargetPort/ContainerPort mismatch  
**Probability ≈ 15 %** – the Service points to a port that the pod does not expose, so even a Ready pod is ignored.

**How to verify**

```bash
# Service ports
kubectl get svc s09-web -n kubexplain-eval -o yaml | grep -A3 port:

# Pod container ports
kubectl get pod s09-bad-readiness -n kubexplain-eval -o yaml | grep -A3 containerPort:
```

*Ensure `spec.ports[].targetPort` (or the numeric value) matches a `containerPort` defined in the pod.*

**How to fix**

* Update the Service to use the correct `targetPort`, or
* Change the pod’s container spec to expose the expected port.

---

## 4️⃣ Namespace mismatch  
**Probability ≈ 5 %** – the Service lives in a different namespace than the pod, so the selector never sees the pod.

**How to verify**

```bash
kubectl get svc s09-web -n kubexplain-eval
kubectl get pod s09-bad-readiness -n kubexplain-eval
```

*Both resources must be in the same namespace.*

**How to fix**

* Move the pod or the Service to the same namespace, or
* Re‑create the Service in the pod’s namespace.

---

## 5️⃣ NetworkPolicy blocks traffic to the pod (rarely affects endpoint creation)  
**Probability ≈ 3 %** – an overly‑restrictive NetworkPolicy can prevent the kube‑proxy from adding the pod to the Service’s load‑balancing set.

**How to verify**

```bash
kubectl get networkpolicy -n kubexplain-eval
```

*Look for policies that select the pod (`podSelector`) but do not allow traffic from the Service’s namespace or from `kube-proxy`.*

**How to fix**

* Relax the policy (add an `ingress` rule that allows traffic from the Service’s pod selector) or delete the policy if it’s not needed.

---

## 6️⃣ kube‑proxy / EndpointSlice controller malfunction (very rare)  
**Probability ≈ 2 %** – a broken kube‑proxy or disabled EndpointSlice controller can leave the Service with no endpoints even though pods are Ready.

**How to verify**

```bash
kubectl get endpointslice -l kubernetes.io/service-name=s09-web -n kubexplain-eval
kubectl logs -n kube-system -l k8s-app=kube-proxy --tail=20
```

*If the EndpointSlice object is missing or empty, and kube‑proxy logs show errors, the control plane component may be at fault.*

**How to fix**

* Restart the kube‑proxy DaemonSet (`kubectl rollout restart ds/kube-proxy -n kube-system`) or investigate the controller manager logs.  
* In most clusters the issue resolves after a restart of the affected component.

---

### Quick “cheat‑sheet” of the verification commands

```bash
# 1️⃣ Readiness state
kubectl describe pod s09-bad-readiness -n kubexplain-eval

# 2️⃣ Service selector vs pod labels
kubectl get svc s09-web -n kubexplain-eval -o jsonpath='{.spec.selector}'
kubectl get pods -n kubexplain-eval -l <selector>

# 3️⃣ Port alignment
kubectl get svc s09-web -n kubexplain-eval -o yaml | grep -A3 port:
kubectl get pod s09-bad-readiness -n kubexplain-eval -o yaml | grep -A3 containerPort:

# 4️⃣ Namespace check
kubectl get svc s09-web -n kubexplain-eval
kubectl get pod s09-bad-readiness -n kubexplain-eval

# 5️⃣ NetworkPolicy audit
kubectl get networkpolicy -n kubexplain-eval

# 6️⃣ EndpointSlice health
kubectl get endpointslice -l kubernetes.io/service-name=s09-web -n kubexplain-eval
kubectl logs -n kube-system -l k8s-app=kube-proxy --tail=20
```

---

### TL;DR – most likely root cause

**A failing readiness probe** is the top culprit. If the pod never reports `Ready=True`, the Service will show no endpoints even though the pod is in the `Running` phase. Fix the probe (or remove it) and the endpoint list should populate automatically. If the probe is fine, move down the list: selector mismatch → port mismatch → namespace mismatch → network policy → control‑plane issues. Each step has a simple `kubectl` check and a straightforward corrective action.
