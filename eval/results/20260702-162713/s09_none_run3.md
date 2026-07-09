# s09 | mode=none | run=3 | 13.8s | needs_search=0

**Q:** My pod 's09-bad-readiness' in namespace 'kubexplain-eval' is running but receives no traffic from service 's09-web' (its endpoints are empty). What is the root cause and how do I fix it? I cannot access the cluster right now, so list the most likely causes in order of probability and explain how to verify and fix each one.

**Expected:** readiness probe fails (wrong path) so the pod is out of endpoints; fix the probe

---

## Most likely reasons why **`s09-bad-readiness`** shows up as *Running* but does **not** appear in the endpoints list of service **`s09‑web`**

| # | Probable cause (ordered by how often it produces an empty‑endpoint list) | How to verify | How to fix |
|---|--------------------------------------------------------------------------|---------------|------------|
| 1 | **Readiness probe is failing** – the pod is *Running* but never becomes *Ready*, so the Service controller removes it from the Endpoints/EndpointSlice objects. | ```bash\nkubectl -n kubexplain-eval describe pod s09-bad-readiness\nkubectl -n kubexplain-eval get pod s09-bad-readiness -o jsonpath='{.status.conditions[?(@.type==\"Ready\")].status}'\n``` Look for `Ready: False` and for events that mention `Readiness probe failed`. | • Inspect the probe definition in the pod spec (`.spec.containers[].readinessProbe`).<br>• Fix the probe (e.g., correct path, port, initialDelaySeconds, timeoutSeconds, or command).<br>• Apply the corrected manifest or patch the pod (if it is managed by a Deployment/ReplicaSet, update the controller and let it roll out a new pod). |
| 2 | **Service selector does not match the pod’s labels** – the Service only selects pods whose labels exactly match its `spec.selector`. If the pod’s labels differ, the Service sees no endpoints. | ```bash\nkubectl -n kubexplain-eval get svc s09-web -o jsonpath='{.spec.selector}'\nkubectl -n kubexplain-eval get pod s09-bad-readiness --show-labels\n``` Compare the two sets of key/value pairs. | • Add the missing label(s) to the pod (or to the controller that creates it) so that they line up with the Service selector.<br>• Or change the Service selector to match the pod’s existing labels. |
| 3 | **Target port mismatch** – the Service’s `port`/`targetPort` points to a container port that the pod does not expose, so the endpoint is considered invalid and is omitted. | ```bash\nkubectl -n kubexplain-eval describe svc s09-web | grep -A2 \"Port:\" \nkubectl -n kubexplain-eval get pod s09-bad-readiness -o jsonpath='{.spec.containers[*].ports[*].containerPort}'\n``` Verify that the `targetPort` (or named port) exists in the pod’s container spec. | • Add the missing containerPort to the pod’s container definition, or<br>• Change the Service’s `targetPort` (or use the port name that exists). |
| 4 | **Pod is in a different namespace** – Services only discover pods in the same namespace. If the pod was created in another namespace (or the Service was created in a different one), the endpoint list will be empty. | ```bash\nkubectl get pod s09-bad-readiness -n kubexplain-eval -o jsonpath='{.metadata.namespace}'\nkubectl get svc s09-web -n kubexplain-eval -o jsonpath='{.metadata.namespace}'\n``` Ensure both are `kubexplain-eval`. | • Re‑create the pod (or its controller) in the correct namespace, or<br>• Move the Service to the pod’s namespace. |
| 5 | **NetworkPolicy blocks the pod from being considered “ready” for the Service** – a restrictive `NetworkPolicy` can prevent the kube‑proxy health‑check traffic that the Service uses to mark the pod as ready, resulting in an empty endpoint list. | ```bash\nkubectl -n kubexplain-eval get networkpolicy -o wide\nkubectl -n kubexplain-eval describe networkpolicy <policy-name>\n``` Look for policies that deny ingress from the kube‑proxy CIDR or from the Service’s pod selector. | • Adjust the NetworkPolicy to allow traffic from the Service’s pod selector (or from the kube‑proxy health‑check IP range).<br>• Or temporarily delete the policy to confirm it is the cause. |
| 6 | **Service is headless (`clusterIP: None`) but no manually created Endpoints** – a headless Service without a selector expects you to create `Endpoints` objects yourself. If none exist, the endpoint list is empty. | ```bash\nkubectl -n kubexplain-eval get svc s09-web -o jsonpath='{.spec.clusterIP}'\n``` `None` indicates a headless Service. | • Add a proper selector to the Service, or<br>• Manually create an `Endpoints` object that lists the pod IPs. |
| 7 | **EndpointSlice controller is malfunctioning** – rare, but if the controller is down or the API server is unable to create EndpointSlice objects, the Service will have no endpoints. | ```bash\nkubectl -n kube-system get pods -l k8s-app=endpoint-controller\nkubectl -n kube-system logs <endpoint-controller-pod>\n``` Look for errors. | • Restart the EndpointSlice controller (or the kube‑controller‑manager if it is built‑in).<br>• Verify that the API server is healthy. |

---

### Quick verification checklist you can run when you regain access

```bash
# 1. Check pod readiness
kubectl -n kubexplain-eval get pod s09-bad-readiness -o wide
kubectl -n kubexplain-eval describe pod s09-bad-readiness | grep -i "readiness"

# 2. Compare service selector vs pod labels
kubectl -n kubexplain-eval get svc s09-web -o jsonpath='{.spec.selector}'
kubectl -n kubexplain-eval get pod s09-bad-readiness --show-labels

# 3. Verify ports
kubectl -n kubexplain-eval describe svc s09-web | grep -A2 "Port:"
kubectl -n kubexplain-eval get pod s09-bad-readiness -o jsonpath='{.spec.containers[*].ports[*].containerPort}'

# 4. Confirm namespace alignment
kubectl get pod s09-bad-readiness -n kubexplain-eval -o jsonpath='{.metadata.namespace}'
kubectl get svc s09-web -n kubexplain-eval -o jsonpath='{.metadata.namespace}'

# 5. Look for restrictive NetworkPolicies
kubectl -n kubexplain-eval get networkpolicy
kubectl -n kubexplain-eval describe networkpolicy <any‑policy>

# 6. Check if the Service is headless
kubectl -n kubexplain-eval get svc s09-web -o jsonpath='{.spec.clusterIP}'
```

Run the relevant commands, identify which of the above items is the culprit, and apply the corresponding fix. In most cases the root cause is a failing readiness probe (≈ 60 % of empty‑endpoint incidents) followed by a selector/label mismatch. Once the pod becomes **Ready** and the selector matches, the Service will automatically populate its endpoints and traffic will start flowing.
