# s09 | mode=static | run=1 | 11.9s | needs_search=0

**Q:** My pod 's09-bad-readiness' in namespace 'kubexplain-eval' is running but receives no traffic from service 's09-web' (its endpoints are empty). What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** readiness probe fails (wrong path) so the pod is out of endpoints; fix the probe

---

## Most likely reasons why **s09‑bad‑readiness** shows up in the pod list but **s09‑web** has an empty endpoint list  

| # | Possible cause | Why it leads to empty endpoints | How to verify (when you have access) | How to fix |
|---|----------------|--------------------------------|--------------------------------------|------------|

### 1. Service selector does **not** match the pod’s labels  

* **Root cause** – The Service `s09‑web` selects pods by a label selector (e.g. `app=s09-web`). If the pod `s09‑bad‑readiness` has a different label set (or a typo), the EndpointSlice controller will never add it to the service’s endpoints.  
* **Verification** –  
  * Inspect the Service’s `spec.selector` field.  
  * List the pod’s labels (`metadata.labels`).  
  * Compare the two; any mismatch (different key, value, or missing label) will keep the pod out of the service.  
* **Fix** – Align the labels: either add the missing label to the pod (or its Deployment/ReplicaSet) or adjust the Service selector to match the existing pod labels. After the change, the EndpointSlice controller will create the endpoints automatically.

---

### 2. Pod fails its **readiness probe** (or has no readiness probe)  

* **Root cause** – A pod that is not **Ready** is excluded from a Service’s endpoints, even though it is Running. If the readiness probe is mis‑configured, always failing, or the container never opens the expected port, the pod stays in `Ready=False`.  
* **Verification** –  
  * Check the pod’s `status.conditions` for `Ready` = `False`.  
  * Look at the `readinessProbe` definition (path, port, initialDelaySeconds, timeoutSeconds, etc.).  
  * Review the pod’s logs for errors that would cause the probe to fail.  
* **Fix** – Correct the probe configuration (e.g., use the right port/path, increase timeouts) or temporarily remove the probe to confirm that the pod then appears in the endpoint list. Once the probe succeeds, the endpoint will be added.

---

### 3. **targetPort** in the Service does not correspond to a container port  

* **Root cause** – The Service forwards traffic to `spec.ports[].targetPort`. If that port is not exposed by the container (or the name is wrong), the endpoint is still created, but kube‑proxy will drop traffic. In some clusters the EndpointSlice may be omitted when the targetPort cannot be resolved.  
* **Verification** –  
  * Examine the Service’s `spec.ports[].targetPort`.  
  * Verify that the pod’s container spec includes a matching `containerPort` (numeric or named).  
* **Fix** – Align the Service’s `targetPort` with the container’s actual listening port (or use the same name). Apply the corrected Service definition.

---

### 4. **Namespace mismatch**  

* **Root cause** – Services only select pods in the **same namespace**. If the pod lives in `kubexplain-eval` but the Service was created in a different namespace (or vice‑versa), the endpoint list will be empty.  
* **Verification** –  
  * Confirm the namespace of both the Service and the pod (`metadata.namespace`).  
* **Fix** – Re‑create the Service in the correct namespace, or move the pod (or its controller) to the Service’s namespace.

---

### 5. Pod uses **hostNetwork** or **hostPort** that prevents endpoint creation  

* **Root cause** – When a pod is configured with `hostNetwork: true` or a `hostPort`, the EndpointSlice controller may skip adding it to a normal ClusterIP service because the pod is reachable via the node’s network directly.  
* **Verification** –  
  * Look for `hostNetwork: true` or `hostPort` in the pod spec.  
* **Fix** – Remove `hostNetwork`/`hostPort` if they are not required, or create a Service of type `NodePort`/`LoadBalancer` that matches the host‑exposed port.

---

### 6. Service type is **ExternalName** (or another non‑cluster IP type)  

* **Root cause** – An `ExternalName` service does not create endpoints; it simply returns a DNS CNAME. If `s09‑web` was mistakenly created as `ExternalName`, the endpoint list will always be empty.  
* **Verification** –  
  * Check `spec.type` of the Service.  
* **Fix** – Change the Service type to `ClusterIP`, `NodePort`, or `LoadBalancer` as appropriate, then re‑apply the manifest.

---

### 7. **EndpointSlice controller** is disabled or malfunctioning  

* **Root cause** – If the controller that populates EndpointSlice resources is not running (e.g., disabled via feature gates or crashed), services will show no endpoints even though pods match.  
* **Verification** –  
  * Look at the `kube-controller-manager` logs for errors related to `endpointslice`.  
  * Verify that the `EndpointSlice` API objects exist (`kubectl get endpointslices`).  
* **Fix** – Restart or re‑enable the controller manager with the `EndpointSlice` feature enabled. Once the controller recovers, endpoints will be populated.

---

### 8. **NetworkPolicy** blocks traffic to the pod, causing it to be considered not ready  

* **Root cause** – A NetworkPolicy that denies all ingress to the pod can cause the readiness probe (which usually uses the pod’s own IP) to fail, keeping the pod out of the service endpoints.  
* **Verification** –  
  * List NetworkPolicies in the namespace and see if any select the pod (`podSelector`).  
  * Check the policy’s `ingress` rules.  
* **Fix** – Adjust the NetworkPolicy to allow traffic on the probe port, or add an explicit allow rule for the Service’s selector.

---

## How to proceed when you regain cluster access  

1. **Start with the selector** – It is the most common cause. Verify the Service’s selector and the pod’s labels.  
2. **Check readiness** – Look at the pod’s `Ready` condition and probe configuration.  
3. **Validate ports** – Ensure Service `targetPort` matches a container port.  
4. **Confirm namespace** – Both objects must be in `kubexplain-eval`.  
5. **Inspect special networking flags** – `hostNetwork`, `hostPort`, Service type, NetworkPolicies.  
6. **If all above are correct, investigate controller health** – Ensure the EndpointSlice controller is running.

Fixing the first issue you encounter will usually cause the endpoint list to populate automatically; no manual endpoint creation is needed. Once the endpoints appear, traffic from `s09‑web` will be routed to `s09‑bad‑readiness` as expected.
